
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
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableSet;

public class Project {
    public static final String BND_WORKSPACE_PROP_NAME = "bnd.workspace";
    public static final String ECLIPSE_WORKSPACE_PROP_NAME = "eclipse.workspace";
    public static final String ECLIPSE_CORE_RESOURCES_PROJECTS = ".metadata/.plugins/org.eclipse.core.resources/.projects";
    static final Path BND_WORKSPACE = Paths.get(System.getProperty(BND_WORKSPACE_PROP_NAME, "Set the bnd.workspace system property in build.gradle's run task."));
    static final Set<String> ECLIPSE_PROJECTS = getKnownEclipseProjects();
    static final Map<String, Project> preCanon = new HashMap<>();
    static final Map<String, Project> canon = new HashMap<>();
    static boolean errorHappened;
    private final String name;
    private final Path bndPath;
    private final boolean isRealProject;
    private final List<String> testPath;
    private List<Project> dependencies;
    private final List<String> buildPath;
    private final Properties bndProps;

    static Set<String> getKnownEclipseProjects() {
        final String prop = System.getProperty(ECLIPSE_WORKSPACE_PROP_NAME);
        if (prop == null) return Collections.emptySet();
        final Path eclipseWorkspace = Paths.get(prop);
        if (!!!Files.isDirectory(eclipseWorkspace)) {
            error("Could not locate eclipse workspace: " + eclipseWorkspace,
                    "To remove this error message, do not set the eclipse.workspace property");
            return Collections.emptySet();
        }
        final Path dotProjectsDir = eclipseWorkspace.resolve(ECLIPSE_CORE_RESOURCES_PROJECTS);
        if (!!!Files.isDirectory(dotProjectsDir)) {
            error("Could not locate .projects dir: " + dotProjectsDir,
                    "Please fix this tool's broken logic and submit a GitHub pull request");
            return Collections.emptySet();
        }
        try {
            return Files.list(dotProjectsDir)
                    .filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .collect(toUnmodifiableSet());
        } catch (IOException e) {
            error("Could not enumerate Eclipse projects despite finding metadata location: " + dotProjectsDir,
                    "Exception was " + e);
            e.printStackTrace();
            return Collections.emptySet();
        }
    }

    private static void error(String message, String...details) {
        errorHappened = true;
        System.err.println("ERROR: " + message);
        for (String detail: details) System.err.println(detail);
    }

    public static void main(String[] args) {
        try {
            System.out.println("================================================================================");
            if (!!!Files.isDirectory(BND_WORKSPACE)) error("Could not locate bnd workspace: " + BND_WORKSPACE);
            else Stream.of(args)
                    .map(Project::getCanonical)
                    .forEach(Project::printInTopologicalOrder);
        } finally {
            System.out.println("================================================================================");
        }
        System.exit(errorHappened ? -1 : 0);
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
        this.bndPath = BND_WORKSPACE.resolve(name).resolve("bnd.bnd");
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

    private String getDisplayFormat() { return ECLIPSE_PROJECTS.contains(name) ? "[%s]" : " %s"; }
}
