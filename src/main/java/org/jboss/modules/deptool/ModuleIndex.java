package org.jboss.modules.deptool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.modules.DependencySpec;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class ModuleIndex {
    private final RootIndex rootIndex;
    private final List<DependencySpec> dependencySpecs = new ArrayList<>();
    private final Map<String, ClassIndex> classes = new HashMap<>();
    private final Map<String, Counter> packageMembers = new HashMap<>();
    private final Map<String, Counter> classRefs = new HashMap<>();
    private final Map<String, Counter> packageRefs = new HashMap<>();
    private final List<DependentInfo> dependents = new ArrayList<>();
    private final List<DependencyInfo> dependencies = new ArrayList<>();
    private final Map<String, Set<ModuleIndex>> dependencyPaths = new HashMap<>();
    private final Map<ModuleIndex, Set<String>> pathsByDependency = new HashMap<>();
    private final Map<String, Set<String>> serviceImplementations = new HashMap<>();
    private final String name;
    private final Set<ModuleIndex> exports = new HashSet<>();
    private String mainClass;

    ModuleIndex(final RootIndex rootIndex, final String name) {
        this.rootIndex = rootIndex;
        this.name = name;
    }

    void addClassIndex(final ClassIndex classIndex) {
        final String name = rootIndex.intern(classIndex.getName());
        classes.put(name, classIndex);
        final int idx = name.lastIndexOf('/');
        if (idx != -1) {
            String packageName = rootIndex.intern(name.substring(0, idx));
            Counter.getCounter(packageMembers, packageName).getAndIncrement();
        }
    }

    RootIndex getRootIndex() {
        return rootIndex;
    }

    void setMainClass(String className) {
        final RootIndex rootIndex = this.rootIndex;
        className = rootIndex.intern(className.replace('.', '/'));
        mainClass = className;
        doAddClassRef(className, rootIndex);
    }

    void addClassRef(String className) {
        final RootIndex rootIndex = this.rootIndex;
        className = rootIndex.intern(className.replace('.', '/'));
        doAddClassRef(className, rootIndex);
    }

    private void doAddClassRef(final String className, final RootIndex rootIndex) {
        final int idx = className.lastIndexOf('/');
        if (idx != -1) {
            String packageName = rootIndex.intern(className.substring(0, idx));
            Counter.getCounter(packageRefs, packageName).getAndIncrement();
        }
        Counter.getCounter(classRefs, className).getAndIncrement();
    }

    String getName() {
        return name;
    }

    Collection<String> getPackageReferences() {
        return packageRefs.keySet();
    }

    Collection<String> getIncludedPackages() {
        return packageMembers.keySet();
    }

    void addDependency(DependencySpec spec) {
        dependencySpecs.add(spec);
    }

    List<DependencySpec> getDependencySpecs() {
        return dependencySpecs;
    }

    void addDependent(DependentInfo info) {
        dependents.add(info);
    }

    void addServiceReference(final String svcIntr, final String svcImpl) {
        serviceImplementations.computeIfAbsent(rootIndex.intern(svcIntr.replace('.', '/')), x -> new LinkedHashSet<>()).add(rootIndex.intern(svcImpl.replace('.', '/')));
        addClassRef(svcIntr);
        addClassRef(svcImpl);
    }

    List<DependentInfo> getDependents() {
        return dependents;
    }

    Set<String> getDependencyPaths() {
        return dependencyPaths.keySet();
    }

    int mapDependencyPackages(final Collection<String> paths, final ModuleIndex target, final DependencySpec dependencySpec) {
        int cnt = 0;
        dependencies.add(new DependencyInfo(target, dependencySpec));
        for (String path : paths) {
            Set<ModuleIndex> set = dependencyPaths.computeIfAbsent(path, k -> new LinkedHashSet<>());
            if (set.add(target)) {
                cnt++;
            }
        }
        Set<String> set = pathsByDependency.computeIfAbsent(target, k -> new HashSet<>());
        set.addAll(paths);
        return cnt;
    }

    Set<String> getPathsByDependency(ModuleIndex dependency) {
        return pathsByDependency.getOrDefault(dependency, Collections.emptySet());
    }

    Set<ModuleIndex> getDependencyModules() {
        return pathsByDependency.keySet();
    }

    Map<String, Set<String>> getServiceImplementations() {
        return serviceImplementations;
    }

    ClassIndex getClassInfo(final String className, final boolean includeDependencies) {
        ClassIndex classIndex = classes.get(className);
        if (classIndex == null && includeDependencies) {
            final int idx = className.lastIndexOf('/');
            if (idx != -1) {
                final Set<ModuleIndex> dependencies = dependencyPaths.get(className.substring(0, idx));
                if (dependencies != null) for (ModuleIndex dependency : dependencies) {
                    classIndex = dependency.getClassInfo(className, false);
                    if (classIndex != null) return classIndex;
                }
            }
        }
        return classIndex;
    }

    List<DependencyInfo> getDependencies() {
        return dependencies;
    }

    void addExportedModule(final ModuleIndex moduleIndex) {
        exports.add(moduleIndex);
    }

    boolean exports(final ModuleIndex moduleIndex) {
        return exports.contains(moduleIndex);
    }

    boolean hasMainClass() {
        return mainClass != null;
    }

    Collection<ClassIndex> getClasses() {
        return classes.values();
    }
}
