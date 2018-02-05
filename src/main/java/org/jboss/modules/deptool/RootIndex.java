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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 */
final class RootIndex {
    private final Map<String, String> internTable = new HashMap<>();
    private final Map<String, ModuleIndex> modules = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final Set<String> strings = new HashSet<>();

    RootIndex() {
    }

    Map<String, ModuleIndex> getModules() {
        return modules;
    }

    void addModuleIndex(ModuleIndex moduleIndex) {
        modules.put(moduleIndex.getName(), moduleIndex);
    }

    String intern(final String name) {
        final String res = internTable.get(name);
        if (res != null) return res;
        internTable.put(name, name);
        return name;
    }

    void addAlias(final String aliasName, final String name) {
        aliases.put(intern(aliasName), intern(name));
    }

    ModuleIndex getModule(final String moduleName) {
        ModuleIndex moduleIndex = modules.get(moduleName);
        if (moduleIndex == null) {
            final String realName = aliases.get(moduleName);
            if (realName != null) {
                moduleIndex = getModule(realName);
            }
        }
        return moduleIndex;
    }

    void addString(final String str) {
        strings.add(intern(str));
    }

    boolean hasString(final String name) {
        return strings.contains(name);
    }
}
