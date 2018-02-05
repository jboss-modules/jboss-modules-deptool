/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.modules.deptool;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 */
final class ClassIndex {
    private final ModuleIndex moduleIndex;
    private String name;
    private final Map<String, Counter> memberClassRefs = new HashMap<>();
    private final Map<String, Counter> memberPackageRefs = new HashMap<>();
    private final Map<String, Counter> otherClassRefs = new HashMap<>();
    private final Map<String, Counter> otherPackageRefs = new HashMap<>();
    private String superClassName;
    private String[] interfaceNames;
    private ClassIndex superClassIndex;
    private final Map<String, ClassIndex> interfaceClassIndexes = new LinkedHashMap<>();

    public ClassIndex(final ModuleIndex moduleIndex) {
        this.moduleIndex = moduleIndex;
    }

    String getName() {
        return name;
    }

    void setName(final String name) {
        this.name = name;
    }

    void addOtherClassRef(String className) {
        addClassRef(className, otherClassRefs, otherPackageRefs);
    }

    void addMemberClassRef(String className) {
        addClassRef(className, memberClassRefs, memberPackageRefs);
    }

    private void addClassRef(String className, Map<String, Counter> classRefs, Map<String, Counter> packageRefs) {
        final RootIndex rootIndex = moduleIndex.getRootIndex();
        className = rootIndex.intern(className.replace('.', '/'));
        final int idx = className.lastIndexOf('/');
        if (idx != -1) {
            String packageName = rootIndex.intern(className.substring(0, idx));
            Counter.getCounter(packageRefs, packageName).getAndIncrement();
        }
        Counter.getCounter(classRefs, className).getAndIncrement();
        moduleIndex.addClassRef(className);
    }

    ClassIndex getSuperClassIndex() {
        return superClassIndex;
    }

    void setSuperClassIndex(final ClassIndex superClassIndex) {
        this.superClassIndex = superClassIndex;
    }

    Map<String, ClassIndex> getInterfaceClassIndexes() {
        return interfaceClassIndexes;
    }

    ModuleIndex getModuleIndex() {
        return moduleIndex;
    }

    String getSuperClassName() {
        return superClassName;
    }

    void setSuperClassName(final String superClassName) {
        this.superClassName = superClassName;
    }

    String[] getInterfaceNames() {
        return interfaceNames;
    }

    void setInterfaceNames(final String[] interfaceNames) {
        this.interfaceNames = interfaceNames;
    }

    void addInterfaceIndex(final ClassIndex interfaceInfo) {
        interfaceClassIndexes.put(interfaceInfo.getName(), interfaceInfo);
    }

    Collection<String> getMemberClassRefs() {
        return memberClassRefs.keySet();
    }
}
