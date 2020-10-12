
/*
 * =============================================================================
 * Copyright (c) 2020 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 * =============================================================================
 */
package io.openliberty.tools.bnddeps;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toList;

public class Project {
    static final Path PROJECT_ROOT = Paths.get(System.getProperty("project.root", System.getProperty("user.home") + "/git/liberty/open-liberty/dev"));
    static final Map<String, Project> preCanon = new HashMap<>();
    static final Map<String, Project> canon = new HashMap<>();
    private final String name;
    private final Path bndPath;
    private final boolean isRealProject;
    private final List<String> declaredDeps = new ArrayList<>();
    private final List<String> testPath;
    private List<Project> dependencies;
    private final List<String> buildPath;
    private final Properties bndProps;

    public static void main(String[] args) throws IOException {
        Stream.of(args)
                .map(Project::getCanonical)
                .forEach(Project::printInTopologicalOrder);
    }

    private void printInTopologicalOrder() {
        Set<Project> list = new LinkedHashSet<>();
        insertInto(list);
        list.stream()
                .map(p -> p.name)
                .forEach(System.out::println);
    }

    private void insertInto(Set<Project> list) {
        dependencies.forEach(child -> child.insertInto(list));
        list.add(this);
    }

    Project(String name) {
        this.name = name;
        this.bndPath = PROJECT_ROOT.resolve(name).resolve("bnd.bnd");
        this.isRealProject = Files.exists(bndPath);
        if (isRealProject) {
            this.bndProps = new Properties();
            try (BufferedReader bndRdr = Files.newBufferedReader(bndPath)) {
                bndProps.load(bndRdr);
            } catch (IOException e) {
                throw new IOError(e);
            }
            this.buildPath = getPathProp("-buildpath");
            this.testPath = getPathProp("-testpath");
        } else {
            this.bndProps = null;
            this.buildPath = null;
            this.testPath = null;
            this.dependencies = emptyList();
        }
    }

    private Project cook() {
        if (null != dependencies) return this;
        dependencies = new ArrayList<>();
        addDeps(this.buildPath);
        addDeps(this.testPath);
        return this;
    }

    private void addDeps(List<String> path) {
        path.stream()
                .map(s -> s.replaceFirst(";.*", ""))
                .map(s -> getRaw(s))
                .filter(p -> p.isRealProject)
                .map(Project::cook)
                .forEach(dependencies::add);
    }

    private List<String> getPathProp(String key) {
        final String prop = bndProps.getProperty(key, "");
        return unmodifiableList(Stream.of(prop.split(",\\s*"))
                .map(s -> s.replaceFirst(";.*", ""))
                .collect(toList()));
    }

    private static Project getCanonical(String name) {
        return canon.computeIfAbsent(name, Project::getCooked);
    }

    private static Project getCooked(String name) {
        return getRaw(name).cook();
    }

    private static Project getRaw(String name) {
        return preCanon.computeIfAbsent(name, Project::new);
    }
}
