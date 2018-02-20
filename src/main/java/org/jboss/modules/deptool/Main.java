package org.jboss.modules.deptool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import nu.xom.Builder;
import nu.xom.Comment;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.Node;
import nu.xom.ParsingException;
import nu.xom.Serializer;
import nu.xom.Text;
import org.jboss.modules.AliasModuleSpec;
import org.jboss.modules.ConcreteModuleSpec;
import org.jboss.modules.DependencySpec;
import org.jboss.modules.IterableResourceLoader;
import org.jboss.modules.LocalModuleFinder;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleDependencySpec;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.PathUtils;
import org.jboss.modules.Resource;
import org.jboss.modules.ResourceLoader;
import org.jboss.modules.ResourceLoaderSpec;
import org.jboss.modules.filter.PathFilter;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class Main {

    static final String SERVICES = "META-INF/services";

    private Main() {}

    private static final Method getResourceLoaders = getAccessibleMethod(ConcreteModuleSpec.class, "getResourceLoaders");
    private static final Method getResourceLoader = getAccessibleMethod(ResourceLoaderSpec.class, "getResourceLoader");

    private static Method getAccessibleMethod(final Class<?> clazz, final String methodName, Class<?>... paramTypes) {
        final Method method;
        try {
            method = clazz.getDeclaredMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        }
        method.setAccessible(true);
        return method;
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            try {
                throw e.getCause();
            } catch (RuntimeException | Error e2) {
                throw e2;
            } catch (Throwable throwable) {
                throw new UndeclaredThrowableException(throwable);
            }
        }
    }

    public static void main(String[] args) throws ModuleLoadException, IOException {
        boolean progress = false;
        boolean print = false;
        boolean warn = false;
        boolean fix = false;
        String[] fixPathNames = null;
        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];
            if (arg.equals("--progress")) {
                progress = true;
            } else if (arg.equals("--print")) {
                print = true;
            } else if (arg.equals("--warn")) {
                warn = true;
            } else if (arg.equals("--fix")) {
                fix = true;
                if (i + 1 < args.length) {
                    fixPathNames = args[++i].split(",");
                }
            }
        }

        final RootIndex rootIndex = new RootIndex();
        final Map<String, Counter> cnt = new LinkedHashMap<>();
        Counter.getCounter(cnt, "print").set(0);
        final Counter modulesCnt = Counter.getCounter(cnt, "modules");
        final Counter aliasCnt = Counter.getCounter(cnt, "module aliases");
        final Counter depCnt = Counter.getCounter(cnt, "dependencies");
        final Counter rrCnt = Counter.getCounter(cnt, "resource roots");
        final Counter classCnt = Counter.getCounter(cnt, "classes");
        final Counter svcFileCnt = Counter.getCounter(cnt, "service files");
        final Counter svcRefCnt = Counter.getCounter(cnt, "service references");

        // stage 1: do a single-pass index over the module roots

        LocalModuleFinder moduleFinder = new LocalModuleFinder();
        final Iterator<String> iterator = moduleFinder.iterateModules((String)null, true);
        showProgress(progress, cnt);
        while (iterator.hasNext()) {
            final String moduleName = rootIndex.intern(iterator.next());
            final ModuleSpec moduleSpec = moduleFinder.findModule(moduleName, Module.getBootModuleLoader());
            if (moduleSpec == null) {
                if (warn) System.err.printf("Warning: unable to find module \"%s\"%n", moduleName);
            } else if (moduleSpec instanceof ConcreteModuleSpec) {
                modulesCnt.getAndIncrement();
                showProgress(progress, cnt);
                final ConcreteModuleSpec concreteModuleSpec = (ConcreteModuleSpec) moduleSpec;
                final ResourceLoaderSpec[] resourceLoaderSpecs = (ResourceLoaderSpec[]) call(getResourceLoaders, concreteModuleSpec);
                final ModuleIndex moduleIndex = new ModuleIndex(rootIndex, moduleName);
                rootIndex.addModuleIndex(moduleIndex);
                final String mainClass = concreteModuleSpec.getMainClass();
                if (mainClass != null) moduleIndex.setMainClass(mainClass);
                for (ResourceLoaderSpec resourceLoaderSpec : resourceLoaderSpecs) {
                    ResourceLoader resourceLoader = (ResourceLoader) call(getResourceLoader, resourceLoaderSpec);
                    if (resourceLoader instanceof IterableResourceLoader) {
                        rrCnt.getAndIncrement();
                        showProgress(progress, cnt);
                        final IterableResourceLoader loader = (IterableResourceLoader) resourceLoader;
                        final Iterator<Resource> resourceIterator = loader.iterateResources("", true);
                        while (resourceIterator.hasNext()) {
                            final Resource resource = resourceIterator.next();
                            final String resourceName = resource.getName();
                            if (resourceName.endsWith(".class")) {
                                classCnt.getAndIncrement();
                                showProgress(progress, cnt);
                                try (InputStream stream = resource.openStream()) {
                                    final ClassReader classReader = new ClassReader(stream);
                                    final ClassIndex classIndex = new ClassIndex(moduleIndex);
                                    classReader.accept(new IndexClassVisitor(null, classIndex), 0);
                                    moduleIndex.addClassIndex(classIndex);
                                }
                            } else if (resourceName.startsWith("META-INF/services/")) {
                                final String svcIntr = rootIndex.intern(resourceName.substring("META-INF/services/".length()).replace('.', '/'));
                                svcFileCnt.getAndIncrement();
                                showProgress(progress, cnt);
                                try (InputStream is = resource.openStream()) {
                                    try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                                        try (BufferedReader br = new BufferedReader(isr)) {
                                            String str;
                                            while ((str = br.readLine()) != null) {
                                                final int idx = str.indexOf('#');
                                                if (idx >= 0) {
                                                    str = str.substring(0, idx);
                                                }
                                                str = str.trim();
                                                if (! str.isEmpty()) {
                                                    svcRefCnt.getAndIncrement();
                                                    moduleIndex.addServiceReference(svcIntr, rootIndex.intern(str.replace('.', '/')));
                                                }
                                            }
                                        }
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace(System.err);
                                }
                            }
                        }
                    } else {
                        // ignoring non-iterable RL
                    }
                }

                final DependencySpec[] dependencies = concreteModuleSpec.getDependencies();
                for (DependencySpec dependency : dependencies) {
                    depCnt.getAndIncrement();
                    showProgress(progress, cnt);
                    moduleIndex.addDependency(dependency);
                }
            } else if (moduleSpec instanceof AliasModuleSpec) {
                final AliasModuleSpec aliasModuleSpec = (AliasModuleSpec) moduleSpec;
                rootIndex.addAlias(aliasModuleSpec.getName(), aliasModuleSpec.getAliasName());
                aliasCnt.getAndIncrement();
                showProgress(progress, cnt);
            }
        }
        Counter.getCounter(cnt, "print").set(0);
        showProgress(progress, cnt);
        if (progress) System.out.println();

        // stage 2: build dependent info

        cnt.clear();
        Counter.getCounter(cnt, "print").set(0);
        final Counter depEdgeCnt = Counter.getCounter(cnt, "dependent edges");

        for (ModuleIndex moduleIndex : rootIndex.getModules().values()) {
            for (DependencySpec dependencySpec : moduleIndex.getDependencySpecs()) {
                if (dependencySpec instanceof ModuleDependencySpec) {
                    final ModuleDependencySpec moduleDependencySpec = (ModuleDependencySpec) dependencySpec;
                    final String moduleName = moduleDependencySpec.getName();
                    final ModuleIndex dependencyModule = rootIndex.getModule(moduleName);
                    if (dependencyModule == null) {
                        if (! moduleDependencySpec.isOptional()) {
                            if (warn) System.err.printf("Warning: unresolved dependency from \"%s\" to \"%s\"%n", moduleIndex.getName(), moduleName);
                        }
                    } else {
                        dependencyModule.addDependent(new DependentInfo(moduleIndex, dependencySpec));
                        depEdgeCnt.getAndIncrement();
                        showProgress(progress, cnt);
                    }
                } else {
                    // todo: system dependency specs...
                }
            }
        }

        Counter.getCounter(cnt, "print").set(0);
        showProgress(progress, cnt);
        if (progress) System.out.println();

        // stage 3: push down path info

        final HashSet<ModuleIndex> visited = new HashSet<>();
        for (ModuleIndex moduleIndex : rootIndex.getModules().values()) {
            processDependents(moduleIndex.getIncludedPackages(), moduleIndex, visited);
        }

        // stage 4: link up supertypes and add inherited package references

        cnt.clear();
        Counter.getCounter(cnt, "print").set(0);
        final Counter classLinkCnt = Counter.getCounter(cnt, "resolved class links");
//        final Counter unresolvedCnt = Counter.getCounter(cnt, "unresolved class links");

        for (ModuleIndex moduleIndex : rootIndex.getModules().values()) {
            // link up supertypes
            for (ClassIndex classIndex : moduleIndex.getClasses()) {
                final String superClassName = classIndex.getSuperClassName();
                if (superClassName != null) {
                    final ClassIndex superClassInfo = moduleIndex.getClassInfo(superClassName, true);
                    if (superClassInfo == null) {
//                        System.out.printf("Unresolved dependency in module \"%s\" for class \"%s\" from class \"%s\"%n", moduleIndex.getName(), superClassName, classIndex.getName());
//                        unresolvedCnt.getAndIncrement();
                    } else {
                        classIndex.setSuperClassIndex(superClassInfo);
                        classLinkCnt.getAndIncrement();
                    }
                }
                for (String interfaceName : classIndex.getInterfaceNames()) {
                    final ClassIndex interfaceInfo = moduleIndex.getClassInfo(interfaceName, true);
                    if (interfaceInfo == null) {
//                        System.out.printf("Unresolved dependency in module \"%s\" for class \"%s\" from class \"%s\"%n", moduleIndex.getName(), superClassName, classIndex.getName());
//                        unresolvedCnt.getAndIncrement();
                    } else {
                        classIndex.addInterfaceIndex(interfaceInfo);
                        classLinkCnt.getAndIncrement();
                    }
                }
            }
        }
        for (ModuleIndex moduleIndex : rootIndex.getModules().values()) {
            for (ClassIndex classIndex : moduleIndex.getClasses()) {
                addReferences(moduleIndex, classIndex);
            }
        }

        Counter.getCounter(cnt, "print").set(0);
        showProgress(progress, cnt);
        if (progress) System.out.println();

        // stage 5: find any unused dependencies

        cnt.clear();
        Counter.getCounter(cnt, "print").set(0);
        final Counter exportsCnt = Counter.getCounter(cnt, "exports");
        final Counter unusedCnt = Counter.getCounter(cnt, "unused dependencies");

        final Map<ModuleIndex, List<DependencyInfo>> unusedDeps = new HashMap<>();

        for (ModuleIndex moduleIndex : rootIndex.getModules().values()) {
            Set<ModuleIndex> visitedDependencies = new HashSet<>();
            outer: for (DependencyInfo dependency : moduleIndex.getDependencies()) {
                final ModuleIndex dependencyModuleIndex = dependency.getDependencyModuleIndex();
                final DependencySpec dependencySpec = dependency.getDependencySpec();
                if (! visitedDependencies.add(dependencyModuleIndex)) {
                    // already found it
                    continue outer;
                }
                if (moduleIndex.exports(dependencyModuleIndex)) {
                    exportsCnt.getAndIncrement();
                    // re-export counts as usage
                    continue outer;
                }
                final Set<String> paths = moduleIndex.getPathsByDependency(dependencyModuleIndex);
                final Collection<String> packageReferences = moduleIndex.getPackageReferences();
                for (String packageReference : packageReferences) {
                    if (paths.contains(packageReference)) {
                        // it's used
                        continue outer;
                    }
                }
                final boolean importsServices = dependencySpec.getImportFilter().accept(SERVICES);
                // any dependency where services are imported is considered used
                if (importsServices) {
                    // it's used
                    continue outer;
                }
                // maybe we simply re-export stuff from this dependency
                for (String packageName : dependency.getDependencyModuleIndex().getIncludedPackages()) {
                    if (dependencySpec.getImportFilter().accept(packageName) && dependencySpec.getExportFilter().accept(packageName)) {
                        continue outer;
                    }
                }
                // or somewhat less likely...
                for (String packageName : dependency.getDependencyModuleIndex().getDependencyPaths()) {
                    if (dependencySpec.getImportFilter().accept(packageName) && dependencySpec.getExportFilter().accept(packageName)) {
                        continue outer;
                    }
                }
                // maybe it's optional
                if (dependencySpec instanceof ModuleDependencySpec && ((ModuleDependencySpec) dependencySpec).isOptional()) {
                    continue outer;
                }
                if (print) System.out.printf("Unused dependency from \"%s\" to \"%s\"%n", moduleIndex.getName(), dependencyModuleIndex.getName());
                unusedDeps.computeIfAbsent(moduleIndex, ignored -> new ArrayList<>()).add(dependency);
                unusedCnt.getAndIncrement();
            }
        }

        if (fix && fixPathNames != null) {
            final Path[] fixPaths = new Path[fixPathNames.length];
            for (int i = 0; i < fixPathNames.length; i ++) {
                fixPaths[i] = Paths.get(fixPathNames[i]);
            }
            for (Map.Entry<ModuleIndex, List<DependencyInfo>> entry : unusedDeps.entrySet()) {
                final ModuleIndex moduleIndex = entry.getKey();
                // find the module under fixPath
                final String moduleName = moduleIndex.getName();
                final String relativePath = PathUtils.basicModuleNameToPath(moduleName);
                if (relativePath == null) {
                    if (warn) System.err.println("Invalid path name for module " + moduleName);
                    continue;
                }
                Path moduleXml = null;
                for (Path fixPath : fixPaths) {
                    moduleXml = fixPath.resolve(relativePath).resolve("module.xml");
                    if (Files.exists(moduleXml)) break;
                    moduleXml = fixPath.resolve("modules/system/layers/base").resolve(relativePath).resolve("module.xml");
                    if (Files.exists(moduleXml)) break;
                    moduleXml = null;
                }
                if (moduleXml == null) {
                    if (warn) System.err.println("Cannot find module.xml for module " + moduleName);
                    continue;
                }
                Builder builder = new Builder(false);
                final Document document;
                try (BufferedReader reader = Files.newBufferedReader(moduleXml, StandardCharsets.UTF_8)) {
                    document = builder.build(reader);
                } catch (ParsingException e) {
                    if (warn) {
                        System.err.print("Failed to parse " + moduleXml + ": ");
                        e.printStackTrace(System.err);
                    }
                    continue;
                }

                final Element rootElement = document.getRootElement();
                final String rootNamespace = rootElement.getNamespaceURI();
                final Element dependenciesElement = rootElement.getFirstChildElement("dependencies", rootNamespace);
                if (dependenciesElement == null) {
                    if (warn) System.err.println("Unexpected missing <dependencies> element");
                    continue;
                }
                final Elements dependencyElements = dependenciesElement.getChildElements("module", rootNamespace);
                final int size = dependencyElements.size();
                final Collection<DependencyInfo> unusedDependencies = entry.getValue();
                for (int i = 0; i < size; i ++) {
                    final Element dependencyElement = dependencyElements.get(i);
                    dep: for (DependencyInfo dependency : unusedDependencies) {
                        final String dependencyName = dependency.getDependencyModuleIndex().getName();
                        if (dependencyName.equals(dependencyElement.getAttributeValue("name"))) {
                            int idx = dependenciesElement.indexOf(dependencyElement);
                            // see if it's marked "keep"
                            for (int fi = idx - 1; fi >= 0; fi --) {
                                final Node sibling = dependenciesElement.getChild(fi);
                                if (sibling instanceof Comment && sibling.getValue().trim().toLowerCase(Locale.ROOT).equals("keep")) {
                                    if (warn) System.err.println("Explicitly preserving dependency " + dependencyName + " in " + moduleXml);
                                    // skip this one explicitly
                                    continue dep;
                                } else if (sibling instanceof Element) {
                                    break;
                                }
                            }
                            dependenciesElement.removeChild(idx);
                            while (idx > 0) {
                                idx --;
                                final Node sibling = dependenciesElement.getChild(idx);
                                if (sibling instanceof Comment) {
                                    dependenciesElement.removeChild(idx);
                                } else if (sibling instanceof Text) {
                                    final Text text = (Text) sibling;
                                    dependenciesElement.removeChild(idx);
                                    if (! text.getValue().trim().isEmpty()) {
                                        if (warn) System.err.println("Removed extra text around " + dependencyElement + " in " + moduleXml);
                                    }
                                } else {
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }

                try (OutputStream out = Files.newOutputStream(moduleXml, StandardOpenOption.TRUNCATE_EXISTING)) {
                    final Serializer serializer = new Serializer(out);
                    serializer.setLineSeparator("\n");
                    serializer.write(document);
                } catch (IOException e) {
                    if (warn) {
                        System.err.print("Failed to write " + moduleXml + ": ");
                        e.printStackTrace(System.err);
                    }
                    continue;
                }
            }
        }

        Counter.getCounter(cnt, "print").set(0);
        showProgress(progress, cnt);
        if (progress) System.out.println();

        // stage 6: find any unused modules

        cnt.clear();
        Counter.getCounter(cnt, "print").set(0);

        int unused = 0;
        for (ModuleIndex moduleIndex : rootIndex.getModules().values()) {
            if (moduleIndex.hasMainClass()) continue;
            final String moduleName = moduleIndex.getName();
            // process old slotty module names
            final ModuleIdentifier moduleIdentifier = ModuleIdentifier.create(moduleName);
            final String namePart = moduleIdentifier.getName();
            final String slotPart = moduleIdentifier.getSlot();
            if (moduleIndex.getDependents().isEmpty() && ! (rootIndex.hasString(moduleName) || rootIndex.hasString(namePart))) {
                if (print) System.out.printf("Unused module \"%s\"%n", moduleName);
                unused++;
            }
        }
        if (print) System.out.printf("Found %d unused modules%n", Integer.valueOf(unused));

    }

    private static void addReferences(final ModuleIndex moduleIndex, final ClassIndex classIndex) {
        final ClassIndex superClassIndex = classIndex.getSuperClassIndex();
        if (superClassIndex != null) {
            for (String classRef : superClassIndex.getMemberClassRefs()) {
                moduleIndex.addClassRef(classRef);
            }
            addReferences(moduleIndex, superClassIndex);
        }
        for (ClassIndex interfaceIndex : classIndex.getInterfaceClassIndexes().values()) {
            for (String classRef : interfaceIndex.getMemberClassRefs()) {
                moduleIndex.addClassRef(classRef);
            }
            addReferences(moduleIndex, interfaceIndex);
        }
    }

    private static ArrayList<String> filteredCopy(Collection<String> original, PathFilter filter) {
        final ArrayList<String> copy = new ArrayList<>(original);
        copy.removeIf(s -> ! filter.accept(s));
        copy.trimToSize();
        return copy;
    }

    private static void processDependents(final Collection<String> remainingPaths, final ModuleIndex moduleIndex, final Set<ModuleIndex> visited) {
        if (visited.add(moduleIndex)) try {
            for (DependentInfo dependentInfo : moduleIndex.getDependents()) {
                final DependencySpec dependencySpec = dependentInfo.getIncomingDependencySpec();
                final ArrayList<String> imported = filteredCopy(remainingPaths, dependencySpec.getImportFilter());
                if (imported.isEmpty()) {
                    continue;
                }
                if (dependentInfo.getDependentModuleIndex().mapDependencyPackages(imported, moduleIndex, dependencySpec) == 0) {
                    continue;
                }
                final ArrayList<String> exported = filteredCopy(imported, dependencySpec.getExportFilter());
                if (exported.isEmpty()) {
                    continue;
                }
                dependentInfo.getDependentModuleIndex().addExportedModule(moduleIndex);
                processDependents(exported, dependentInfo.getDependentModuleIndex(), visited);
            }
        } finally {
            visited.remove(moduleIndex);
        }
    }

    private static void showProgress(final boolean progress, final Map<String, Counter> cnt) {
        if (progress) {
            if (Counter.getCounter(cnt, "print").getAndIncrement() % 137 == 0) {
                System.out.print("\rProcessed ");
                Iterator<Map.Entry<String, Counter>> iterator = cnt.entrySet().iterator();
                while (iterator.hasNext()) {
                    final Map.Entry<String, Counter> entry = iterator.next();
                    final String key = entry.getKey();
                    if (key.equals("print")) continue;
                    System.out.print(entry.getValue().get());
                    System.out.print(" ");
                    System.out.print(key);
                    if (iterator.hasNext()) System.out.print(", ");
                }
            }
        }
    }

    static void addOtherReference(ClassIndex classIndex, Type type) {
        switch (type.getSort()) {
            case Type.ARRAY: {
                addOtherReference(classIndex, type.getElementType());
                break;
            }
            case Type.OBJECT: {
                classIndex.addOtherClassRef(type.getClassName());
                break;
            }
            case Type.METHOD: {
                addOtherReference(classIndex, type.getReturnType());
                for (Type argType : type.getArgumentTypes()) {
                    addOtherReference(classIndex, argType);
                }
                break;
            }
        }
    }

    static void addMemberReference(ClassIndex classIndex, Type type) {
        switch (type.getSort()) {
            case Type.ARRAY: {
                addMemberReference(classIndex, type.getElementType());
                break;
            }
            case Type.OBJECT: {
                classIndex.addMemberClassRef(type.getClassName());
                break;
            }
            case Type.METHOD: {
                addMemberReference(classIndex, type.getReturnType());
                for (Type argType : type.getArgumentTypes()) {
                    addMemberReference(classIndex, argType);
                }
                break;
            }
        }
    }

    static class IndexClassVisitor extends ClassVisitor {
        private final ClassIndex classIndex;

        IndexClassVisitor(final ClassVisitor delegate, final ClassIndex classIndex) {
            super(Opcodes.ASM6, delegate);
            this.classIndex = classIndex;
        }

        public void visit(final int version, final int access, final String name, final String signature, final String superName, final String[] interfaces) {
            final RootIndex rootIndex = classIndex.getModuleIndex().getRootIndex();
            if (superName != null) {
                classIndex.setSuperClassName(rootIndex.intern(superName.replace('.', '/')));
                classIndex.addMemberClassRef(superName);
            }
            String[] inames = new String[interfaces.length];
            for (int i = 0; i < interfaces.length; i++) {
                final String iName = interfaces[i];
                classIndex.addMemberClassRef(iName);
                inames[i] = rootIndex.intern(iName.replace('.', '/'));
            }
            classIndex.setInterfaceNames(inames);
            classIndex.setName(name);
        }

        public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitAnnotation(desc, visible), classIndex);
        }

        public AnnotationVisitor visitTypeAnnotation(final int typeRef, final TypePath typePath, final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitTypeAnnotation(typeRef, typePath, desc, visible), classIndex);
        }

        public FieldVisitor visitField(final int access, final String name, String desc, final String signature, final Object value) {
            while (desc.startsWith("[")) {
                desc = desc.substring(1);
            }
            if ((access & Opcodes.ACC_PUBLIC) != 0 || (access & Opcodes.ACC_PROTECTED) != 0) {
                addMemberReference(classIndex, Type.getType(desc));
            } else {
                addOtherReference(classIndex, Type.getType(desc));
            }
            return new IndexFieldVisitor(super.visitField(access, name, desc, signature, value), classIndex);
        }

        public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
            if ((access & Opcodes.ACC_STATIC) == 0 && (access & Opcodes.ACC_PRIVATE) == 0) {
                addOtherReference(classIndex, Type.getType(desc));
            } else {
                addMemberReference(classIndex, Type.getType(desc));
            }
            return new IndexMethodVisitor(super.visitMethod(access, name, desc, signature, exceptions), classIndex);
        }

        public void visitEnd() {
            super.visitEnd();
        }
    }

    static class IndexAnnotationVisitor extends AnnotationVisitor {
        private final ClassIndex classIndex;

        IndexAnnotationVisitor(final AnnotationVisitor delegate, final ClassIndex classIndex) {
            super(Opcodes.ASM6, delegate);
            this.classIndex = classIndex;
        }

        public void visit(final String name, final Object value) {
            if (value instanceof Type) {
                String className = ((Type) value).getClassName();
                classIndex.addOtherClassRef(className);
            }
            super.visit(name, value);
        }

        public void visitEnum(final String name, final String desc, final String value) {
            super.visitEnum(name, desc, value);
        }

        public AnnotationVisitor visitAnnotation(final String name, final String desc) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitAnnotation(name, desc), classIndex);
        }

        public AnnotationVisitor visitArray(final String name) {
            return new IndexAnnotationVisitor(super.visitArray(name), classIndex);
        }

        public void visitEnd() {
            super.visitEnd();
        }
    }

    static class IndexFieldVisitor extends FieldVisitor {
        private final ClassIndex classIndex;

        IndexFieldVisitor(final FieldVisitor delegate, final ClassIndex classIndex) {
            super(Opcodes.ASM6, delegate);
            this.classIndex = classIndex;
        }

        public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitAnnotation(desc, visible), classIndex);
        }

        public AnnotationVisitor visitTypeAnnotation(final int typeRef, final TypePath typePath, final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitTypeAnnotation(typeRef, typePath, desc, visible), classIndex);
        }

        public void visitEnd() {
            super.visitEnd();
        }
    }

    static class IndexMethodVisitor extends MethodVisitor {

        private final ClassIndex classIndex;

        IndexMethodVisitor(final MethodVisitor delegate, final ClassIndex classIndex) {
            super(Opcodes.ASM6, delegate);
            this.classIndex = classIndex;
        }

        public void visitMethodInsn(final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            addMemberReference(classIndex, Type.getType(desc));
            classIndex.addOtherClassRef(owner);
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        public void visitTypeInsn(final int opcode, final String type) {
            classIndex.addOtherClassRef(type);
            super.visitTypeInsn(opcode, type);
        }

        public void visitInvokeDynamicInsn(final String name, final String desc, final Handle bsm, final Object... bsmArgs) {
            addOtherReference(classIndex, Type.getType(desc));
            super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        }

        public void visitLocalVariable(final String name, final String desc, final String signature, final Label start, final Label end, final int index) {
            addOtherReference(classIndex, Type.getType(desc));
            super.visitLocalVariable(name, desc, signature, start, end, index);
        }

        public AnnotationVisitor visitLocalVariableAnnotation(final int typeRef, final TypePath typePath, final Label[] start, final Label[] end, final int[] index, final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index, desc, visible), classIndex);
        }

        public AnnotationVisitor visitParameterAnnotation(final int parameter, final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitParameterAnnotation(parameter, desc, visible), classIndex);
        }

        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            if (type != null) classIndex.addOtherClassRef(type);
            super.visitTryCatchBlock(start, end, handler, type);
        }

        public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitAnnotation(desc, visible), classIndex);
        }

        public AnnotationVisitor visitTypeAnnotation(final int typeRef, final TypePath typePath, final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitTypeAnnotation(typeRef, typePath, desc, visible), classIndex);
        }

        public void visitLdcInsn(final Object cst) {
            if (cst instanceof String) {
                final RootIndex rootIndex = classIndex.getModuleIndex().getRootIndex();
                rootIndex.addString((String) cst);
            } else if (cst instanceof Type) {
                addOtherReference(classIndex, (Type) cst);
            }
            super.visitLdcInsn(cst);
        }

        public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
            addOtherReference(classIndex, Type.getType(desc));
            classIndex.addOtherClassRef(owner);
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        public void visitMultiANewArrayInsn(final String desc, final int dims) {
            addOtherReference(classIndex, Type.getType(desc));
            super.visitMultiANewArrayInsn(desc, dims);
        }

        public AnnotationVisitor visitAnnotationDefault() {
            return new IndexAnnotationVisitor(super.visitAnnotationDefault(), classIndex);
        }

        public AnnotationVisitor visitTryCatchAnnotation(final int typeRef, final TypePath typePath, final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitTryCatchAnnotation(typeRef, typePath, desc, visible), classIndex);
        }

        public AnnotationVisitor visitInsnAnnotation(final int typeRef, final TypePath typePath, final String desc, final boolean visible) {
            addOtherReference(classIndex, Type.getType(desc));
            return new IndexAnnotationVisitor(super.visitInsnAnnotation(typeRef, typePath, desc, visible), classIndex);
        }

        public void visitEnd() {
            super.visitEnd();
        }
    }
}
