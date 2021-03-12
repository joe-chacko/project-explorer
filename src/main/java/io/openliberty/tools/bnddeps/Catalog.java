/*
 * =============================================================================
 * Copyright (c) 2021 IBM Corporation and others.
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

class Catalog {
    final Map<String, Project> preCanon = new HashMap<>();
    final Map<String, Project> canon = new HashMap<>();
    final Path bndWorkspace;
    final Set<String> knownProjects;

    Catalog(Path bndWorkspace, Set<String> knownProjects) {
        this.bndWorkspace = bndWorkspace;
        this.knownProjects = knownProjects;
    }

    Project getCanonical(String name) {
        return canon.computeIfAbsent(name, this::getCooked);
    }

    private Project getCooked(String name) {
        return getRaw(name).cook();
    }

    private Project getRaw(String name) {
        return preCanon.computeIfAbsent(name, Project::new);
    }

    class Project {
        private final String name;
        private final Path root;
        private final Path bndPath;
        private final boolean isRealProject;
        private final List<String> testPath;
        private List<Project> dependencies;
        private final List<String> buildPath;
        private final Properties bndProps;

        Project(String name) {
            this.name = name;
            this.root = bndWorkspace.resolve(name);
            this.bndPath = root.resolve("bnd.bnd");
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

        void printInTopologicalOrder() {
            if (!isRealProject) throw new IllegalStateException("Project directory does not exist: " + root.toString());
            dfs()
                    .map(Project::displayName)
                    .forEach(System.out::println);
        }

        // depth first search on current Project
        private Stream<Project> dfs() {
            // find children in order of first occurrence in a DFS
            return dfs0(new LinkedHashSet<Project>()).stream();
        }

        private Set<Project> dfs0(Set<Project> list) {
            dependencies.forEach(child -> child.dfs0(list));
            list.add(this);
            return list;
        }

        private String displayName() {
            return (knownProjects.contains(name))
                    ? String.format("[%s]", name)
                    : String.format(" %s \t->\t%s", name, getRoot());
        }

        private Path getRoot() {
            try {
                return root.toRealPath();
            } catch (IOException e) {
                return root;
            }
        }
    }
}
