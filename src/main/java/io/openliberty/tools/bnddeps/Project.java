
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
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableSet;

public class Project {
    static final Path PROJECT_ROOT = Paths.get(System.getProperty("project.root", System.getProperty("user.home") + "/git/liberty/open-liberty/dev"));
    static final Path WORKSPACE_ROOT = System.getProperties().containsKey("workspace.root")
            ? Paths.get(System.getProperty("workspace.root"))
            : PROJECT_ROOT.getParent().getParent().resolve("eclipse");
    static final Path PROJECT_METADATA_PATH = WORKSPACE_ROOT.resolve(".metadata/.plugins/org.eclipse.core.resources/.projects");
    static final Set<String> KNOWN_PROJECTS;
    static final Map<String, Project> preCanon = new HashMap<>();
    static final Map<String, Project> canon = new HashMap<>();
    private final String name;
    private final Path bndPath;
    private final boolean isRealProject;
    private final List<String> testPath;
    private List<Project> dependencies;
    private final List<String> buildPath;
    private final Properties bndProps;

    static {
        try {
             KNOWN_PROJECTS = Files.list(PROJECT_METADATA_PATH)
                    .filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .collect(toUnmodifiableSet());
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static void main(String[] args) {
        Stream.of(args)
                .map(Project::getCanonical)
                .forEach(Project::printInTopologicalOrder);
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

    private void printInTopologicalOrder() {
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

    private String displayName() { return String.format(getDisplayFormat(), name); }

    private String getDisplayFormat() { return KNOWN_PROJECTS.contains(name) ? "[%s]" : " %s"; }
}
