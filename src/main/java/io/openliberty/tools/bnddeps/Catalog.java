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
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toList;

class Catalog {
    final Map<String, Project> preCanon = new HashMap<>();
    final Map<String, Project> canon = new HashMap<>();
    final Path bndWorkspace;
    final Set<String> knownProjects;
    boolean showAll;

    Catalog(Path bndWorkspace, Set<String> knownProjects) throws IOException {
        this.bndWorkspace = bndWorkspace;
        this.knownProjects = knownProjects;
        // Initialise projects for every subdirectory that has a bnd file
        Files.list(bndWorkspace)
                .filter(Files::isDirectory)
                .filter(p -> Files.exists(p.resolve("bnd.bnd")))
                .map(Path::getFileName)
                .map(Path::toString)
                .map(Project::new)
                // store the project against the folder name
                .peek(project -> preCanon.put(project.name, project))
                .filter(Project::symbolicNameDiffersFromName)
                // store the project against the symbolic name, if different
                .forEach(project -> preCanon.put(project.bundleSymbolicName, project));
    }

    Project getCanonical(String name) {
        return canon.computeIfAbsent(name, this::getCooked);
    }

    private Project getCooked(String name) {
        return getRaw(name).cook();
    }

    private Project getRaw(String name) {
        try {
            return preCanon.computeIfAbsent(name, Project::new);
        } catch (Exception e) {
            // could not construct project, mention this and continue
            System.err.printf("Could not explore project with name %s: %s", name, e);
            return null;
        }
    }

    void showAllProjects(boolean showAll) {
        this.showAll = showAll;
    }

    class Project {
        private final String name;
        private final Path root;
        private final Path bndPath;
        private final Path bndOverridesPath;
        private final boolean isRealProject;
        private final List<String> testPath;
        private final String bundleSymbolicName;
        private List<Project> dependencies;
        private final List<String> buildPath;
        private final Properties bndProps;

        Project(String name) {
            this.name = name;
            this.root = bndWorkspace.resolve(name);
            this.bndPath = root.resolve("bnd.bnd");
            this.bndOverridesPath = root.resolve("bnd.overrides");
            this.isRealProject = Files.exists(bndPath);
            if (isRealProject) {
                this.bndProps = new Properties();
                try (BufferedReader bndRdr = Files.newBufferedReader(bndPath)) {
                    bndProps.load(bndRdr);
                    if (Files.exists(bndOverridesPath)) {
                        try (BufferedReader overrideRdr = Files.newBufferedReader(bndOverridesPath)) {
                            bndProps.load(overrideRdr);
                        }
                    }
                } catch (IOException e) {
                    throw new IOError(e);
                }
                this.bundleSymbolicName = bndProps.getProperty("Bundle-SymbolicName");
                this.buildPath = getPathProp("-buildpath");
                this.testPath = getPathProp("-testpath");
            } else {
                this.bndProps = null;
                this.bundleSymbolicName = name;
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
                    .filter(Objects::nonNull)
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
                    .filter(Project::isVisible)
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

        boolean isUnknown() { return !knownProjects.contains(name); }

        boolean isVisible() { return showAll || isUnknown(); }

        private String displayName() {
            return isUnknown() ?
                    String.format("  %s \t->\t%s", name, getRoot()) :
                    String.format(" [%s]", name);
        }

        private Path getRoot() {
            try {
                return root.toRealPath();
            } catch (IOException e) {
                return root;
            }
        }

        boolean symbolicNameDiffersFromName() {
            return Optional.ofNullable(bundleSymbolicName)
                    .filter(not(String::isEmpty))
                    .filter(not(name::equals))
                    .isPresent();
        }
    }

    private static <T> Predicate<T> not(Predicate<T> predicate) { return t -> !!!predicate.test(t); }
}
