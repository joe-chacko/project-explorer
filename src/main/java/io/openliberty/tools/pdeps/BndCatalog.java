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
package io.openliberty.tools.pdeps;

import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Collections.unmodifiableList;
import static java.util.Comparator.comparing;
import static java.util.Spliterator.DISTINCT;
import static java.util.Spliterator.NONNULL;
import static java.util.Spliterator.ORDERED;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toUnmodifiableList;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME;

class BndCatalog {
    private static<T> SimpleDirectedGraph<T, DefaultEdge> newGraph() {
        return new SimpleDirectedGraph<>(DefaultEdge.class);
    }

    private final SimpleDirectedGraph<Project, DefaultEdge> digraph = newGraph();
    private final Map<String, Project> projectIndex = new TreeMap<>();

    BndCatalog(Path bndWorkspace) throws IOException {
        // add the vertices
        Files.list(bndWorkspace)
                .filter(Files::isDirectory) // for every subdirectory
                .filter(p -> Files.exists(p.resolve("bnd.bnd"))) // that has a bnd file
                .map(Project::new) // create a Project object
                .forEach(digraph::addVertex); // add it as a vertex to the graph

        // index projects by name
        digraph.vertexSet().stream()
                .peek(p -> projectIndex.put(p.name, p))
                .filter(Project::symbolicNameDiffersFromName)
                .forEach(p -> projectIndex.put(p.symbolicName, p));

        // add the edges
        digraph.vertexSet().stream().forEach(p -> {
            p.dependencies.stream()
                    .map(projectIndex::get)
                    .filter(Objects::nonNull)
                    .filter(not(p::equals))
                    .forEach(q -> digraph.addEdge(p, q));
        });
    }

    private Project find(String name) {
        Project result = projectIndex.get(name);
        if (null == result) throw new Error("No project found with name \"" + name + '"');
        return result;
    }

    Stream<Path> getRequiredProjectPaths(Collection<String> projectNames) {
        // collect the named projects to start with
        var projects = projectNames.stream()
                .map(this::find)
                .collect(Collectors.toUnmodifiableSet());
        var results = new HashSet<Project>();
        // collect all known dependencies, breadth-first
        while (!projects.isEmpty()) {
            results.addAll(projects);
            projects = projects.stream()
                    .sequential()
                    .map(digraph::outgoingEdgesOf)
                    .flatMap(Set::stream)
                    .map(digraph::getEdgeTarget)
                    .filter(not(results::contains))
                    .collect(toUnmodifiableSet());
        }
        var deps = new AsSubgraph<>(digraph, results);
        var rDeps = new EdgeReversedGraph<>(deps);
        var topo = new TopologicalOrderIterator<>(rDeps, comparing(p -> p.name));
        return stream(topo).map(p -> p.root);
    }

    private static <T> Stream<T> stream(Iterator<T> iterator) {
        var spl = Spliterators.spliteratorUnknownSize(iterator, ORDERED);
        return StreamSupport.stream(spl, false);
    }

    static final class Project {
        final Path root;
        final String name;
        final String symbolicName;
        final List<String> dependencies;

        Project(Path root) {
            this.root = root;
            this.name = root.getFileName().toString();
            Properties props = getBndProps(root);
            this.symbolicName = props.getProperty(BUNDLE_SYMBOLICNAME);
            List<String> deps = new ArrayList<>();
            deps.addAll(getPathProp(props, "-buildpath"));
            deps.addAll(getPathProp(props, "-testpath"));
            this.dependencies = unmodifiableList(deps);
        }

        static Properties getBndProps(Path root) {
            Path bndPath = root.resolve("bnd.bnd");
            Path bndOverridesPath = root.resolve("bnd.overrides");
            Properties bndProps = new Properties();
            try (var bndRdr = Files.newBufferedReader(bndPath)) {
                bndProps.load(bndRdr);
                if (Files.exists(bndOverridesPath)) {
                    try (var overrideRdr = Files.newBufferedReader(bndOverridesPath)) {
                        bndProps.load(overrideRdr);
                    }
                }
            } catch (IOException e) {
                throw new IOError(e);
            }
            return bndProps;
        }

        private static List<String> getPathProp(Properties props, String key) {
            String val = props.getProperty(key, "");
            return Stream.of(val.split(",\\s*"))
                    .map(s -> s.replaceFirst(";.*", ""))
                    .collect(toUnmodifiableList());
        }

        boolean symbolicNameDiffersFromName() {
            return Objects.nonNull(symbolicName) && !Objects.equals(name, symbolicName);
        }

        @Override
        public String toString() { return name; }
    }

    private static <T> Predicate<T> not(Predicate<T> predicate) { return t -> !!!predicate.test(t); }
}
