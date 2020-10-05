package io.openliberty.tools.oldeps;/*
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

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;

public class Project {
    static final Path PROJECT_ROOT = Paths.get(System.getProperty("project.root", "/Users/chackoj/git/liberty/open-liberty/dev"));
    static final Map<String, Project> preCanon = new HashMap<>();
    static final Map<String, Project> canon = new HashMap<>();
    private final String name;
    private List<Project> preDependencies = new ArrayList<>();
    private final List<Project> dependencies = unmodifiableList(preDependencies);

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
    }

    private Project cook() {
        if (preDependencies == null) return this;
        try {
            final Path bndPath = PROJECT_ROOT.resolve(name).resolve("bnd.bnd");
            if (Files.exists(bndPath)) {
                final BufferedReader bndRdr;
                bndRdr = Files.newBufferedReader(bndPath);
                final Properties bndProps = new Properties();
                bndProps.load(bndRdr);
                final String buildPath = bndProps.getProperty("-buildpath");
                Stream.of(buildPath.split(",\\s*"))
                        .map(s -> s.replaceFirst(";.*", ""))
                        .map(s -> getRaw(s))
                        .forEach(preDependencies::add);
                dependencies.forEach(Project::cook);
            }
            return this;
        } catch (IOException e) {
            throw new IOError(e);
        } finally {
            // burn the bridge that allows new  dependencies to be added
            preDependencies = null;
        }
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
