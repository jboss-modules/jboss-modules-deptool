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

import java.util.Map;

/**
 */
class Counter {
    private int value;

    Counter() {
    }

    Counter(final int value) {
        this.value = value;
    }

    static Counter getCounter(Map<String, Counter> map, String name) {
        Counter counter = map.get(name);
        if (counter == null) {
            counter = new Counter();
            map.put(name, counter);
        }
        return counter;
    }

    int getAndIncrement() {
        return value++;
    }

    int incrementAndGet() {
        return ++value;
    }

    int get() {
        return value;
    }

    int addAndGet(int amt) {
        return value += amt;
    }

    int getAndAdd(int amt) {
        try {
            return value;
        } finally {
            value += amt;
        }
    }

    void set(final int i) {
        value = i;
    }
}
