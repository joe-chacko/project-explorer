/*
 * =============================================================================
 * Copyright (c) 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 * =============================================================================
 */

package io.openliberty.tools.pdeps;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.PropertiesDefaultProvider;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Collections.unmodifiableSet;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;

@Command(
        name = "px",
        mixinStandardHelpOptions = true,
        description = "Project eXplorer - explore relationships between projects in a bnd workspace " +
                "and their corresponding projects in an eclipse workspace",
        version = "Project eXplorer 0.8",
        subcommands = HelpCommand.class, // other subcommands are annotated methods
        defaultValueProvider = PropertiesDefaultProvider.class
)
public class ProjectExplorer {
    @Option(names = {"-b", "--bnd-workspace"}, defaultValue = ".", description = "Location of the bnd workspace (default=.)")
    Path bndWorkspace;

    @Option(names = {"-e", "--eclipse-workspace"}, defaultValue = "../../eclipse", description = "Location of the eclipse workspace (default=.)")
    Path eclipseWorkspace;
    private BndCatalog catalog;
    private Set<String> knownProjects;

    public static void main(String...args) {
        System.exit(new CommandLine(new ProjectExplorer())
                .setAbbreviatedSubcommandsAllowed(true)
                .execute(args));
    }

    @Command(description = "Lists specified project(s) and their transitive dependencies in dependency order. Full paths are displayed, for ease of pasting into Eclipse's Import Project... dialog. ")
    void deps(
            @Option(names = {"-a", "--show-all"}, description = "Includes projects already in the Eclipse workspace.")
            boolean showAll,
            @Option(names = {"-n", "--print-names"}, description = "Print names of projects rather than paths.")
            boolean printNames,
            @Option(names = {"-e", "--eclipse-ordering"}, description = "Use the unusual ordering of projects in eclipse's import-existing-projects dialog box.")
            boolean eclipseOrdering,
            @Parameters(paramLabel = "project", arity = "1..*", description = "The project(s) whose dependencies are to be displayed.")
            List<String> projectNames) {
        getKnownProjects();
        getBndCatalog();
        var paths = catalog.getRequiredProjectPaths(projectNames)
                .filter(p -> showAll || !knownProjects.contains(p.getFileName().toString()))
                .map(printNames ? Path::getFileName : Path::toAbsolutePath);
        if (eclipseOrdering) paths = paths.sorted(EclipseOrdering.COMPARATOR);
        paths.forEach(System.out::println);
    }

    @Command(description = "Add specified pattern(s) to the focus list. These patterns indicate which projects you intend to edit. If no pattern is specified, just list all the existing focus projects.")
    void focus(
            @Parameters(paramLabel = "patterns", description = "The names (or patterns) of project(s) to be edited in eclipse.")
            List<String> patterns) {
        var focusList = getFocusList();
        var matchingProjects = getMatchingProjects(focusList);
        if (focusList.isEmpty()) {
            System.out.println("There are no current focus projects.");
        } else {
            System.out.println("Current focus projects:");
            matchingProjects
                    .stream()
                    .map("\t"::concat) // indent the focus list
                    .forEach(System.out::println);
        }
        if (null == patterns) return;
        // there were some patterns, so compute the new list
        var newFocusList = new ArrayList<>(focusList);
        newFocusList.addAll(patterns);
        // write this out to file
        try (FileWriter fw = new FileWriter(getFocusListFile().toFile()); PrintWriter pw = new PrintWriter(fw)) {
            newFocusList.forEach(pw::println);
        } catch (IOException e) {
            throw error("Failed to open focus file for writing");
        }
        // compute the changes
        var newMatchingProjects = getMatchingProjects(newFocusList);
        Set<String> added = new TreeSet<>(newMatchingProjects);
        added.removeAll(matchingProjects);
        Set<String> deleted = new TreeSet<>(matchingProjects);
        deleted.removeAll(newMatchingProjects);
        if (!added.isEmpty()) {
            System.out.println("New focus projects:");
            added.stream()
                    .map("\t"::concat)
                    .forEach(System.out::println);
        }
        if (!deleted.isEmpty()) {
            System.out.println("Removed focus projects:");
            deleted.stream()
                    .map("\t"::concat)
                    .forEach(System.out::println);
        }
    }

    @Command(description = "Lists projects needed by but missing from Eclipse. Full paths are displayed, for ease of pasting into Eclipse's Import Project... dialog. ")
    void gaps() {
        getKnownProjects();
        getBndCatalog();
        catalog.getRequiredProjectPaths(knownProjects, true)
                .filter(p -> !knownProjects.contains(p.getFileName().toString()))
                .map(Path::toAbsolutePath)
                .forEach(System.out::println);
    }

    @Command(description = "show projects already known to Eclipse")
    void known() {
        getKnownProjects();
        knownProjects.forEach(System.out::println);
    }

    @Command(aliases = "ls", description = "Lists projects matching the specified patterns.")
    void list(@Parameters(paramLabel = "pattern", arity = "0..*", description = "The patterns to match using filesystem globbing")
              List<String> patterns) {
        Optional.ofNullable(patterns)
                .filter(not(List::isEmpty))
                .map(this::getMatchingProjects)
                .orElseGet(this::getAllProjects)
                .forEach(System.out::println);
    }

    @Command(description = "show known projects that are not required by any other projects")
    void roots() {
        getKnownProjects();
        getBndCatalog();
        var graph = catalog.getProjectAndDependencySubgraph(knownProjects, true);
        graph.vertexSet().stream()
                .filter(p -> graph.inDegreeOf(p) == 0)
                .map(p -> p.name)
                .forEach(System.out::println);
    }

    @Command(name = "uses", description = "Lists projects that depend directly on specified project(s). Projects are listed by name regardless of inclusion in Eclipse workspace.")
    void uses(
            @Parameters(arity = "1..*", description = "The project(s) whose dependents are to be displayed.")
                    List<String> projectNames
    ) {
        getKnownProjects();
        getBndCatalog();
        catalog.getDependentProjectPaths(projectNames)
                .map(Path::getFileName)
                .forEach(System.out::println);
    }

    private BndCatalog getBndCatalog() {
        if (this.catalog == null) {
            if (Files.isDirectory(bndWorkspace)) {
                try {
                    this.catalog = new BndCatalog(bndWorkspace);
                } catch (IOException e) {
                    throw error("Could not inspect bnd workspace: " + bndWorkspace);
                }
            } else {
                throw error("Could not locate bnd workspace: " + bndWorkspace);
            }
        }
        return this.catalog;
    }

    private Set<String> getKnownProjects() {
        if (this.knownProjects == null) {
            Path dotProjectsDir = getEclipseDotProjectsDir();
            try {
                this.knownProjects = unmodifiableSet(Files.list(dotProjectsDir)
                        .filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .collect(toSet()));
            } catch (IOException e) {
                throw error("Could not enumerate Eclipse projects despite finding metadata location: " + dotProjectsDir,
                        "Exception was " + e);
            }
        }
        return this.knownProjects;
    }

    private Set<String> getMatchingProjects(List<String> patterns) {
        Set<String> set = new TreeSet<>();
        for (String pattern: patterns) {
            try {
                boolean exclude = pattern.startsWith("!");
                if (exclude) pattern = pattern.substring(1);
                getBndCatalog().findProjects(pattern).map(Path::getFileName).map(Path::toString).forEach(exclude ? set::remove : set::add);
            } catch (NoSuchElementException e) {
                System.err.printf("error: no files found matching pattern '%s'%n", pattern);
            }
        }
        return set;
    }

    private Set<String> getAllProjects() {
        return getBndCatalog()
                .allProjects()
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(toCollection(TreeSet::new));
    }

    private Path getEclipseDotProjectsDir() { return verifyDir(".projects dir", getEclipseWorkspace().resolve(".metadata/.plugins/org.eclipse.core.resources/.projects")); }

    private List<String> getFocusList() {
        try {
            return Files.readAllLines(getFocusListFile());
        } catch (IOException e) {
            throw error("Could not open the focus file for reading");
        }
    }

    private Path getFocusListFile() { return verifyOrCreateFile("focus list file", getEclipsePxDir().resolve("focus-list")); }
    private Path getEclipsePxDir() { return verifyOrCreateDir("eclipse px settings dir", getEclipseWorkspace().resolve(".px")); }
    private Path getEclipseWorkspace() { return verifyDir("eclipse workspace", eclipseWorkspace); }

    private static Path verifyOrCreateFile(String desc, Path file) {
        if (Files.exists(file) && !Files.isDirectory(file) && Files.isWritable(file)) return file;
        try {
            return Files.createFile(file);
        } catch (IOException e) {
            throw error("Could not create " + desc + ": " + file);
        }
    }

    private static Path verifyOrCreateDir(String desc, Path dir) {
        if (Files.isDirectory(dir)) return dir;
        if (Files.exists(dir)) throw error("Could not overwrite " + desc + " as directory: " + dir);
        try {
            return Files.createDirectory(dir);
        } catch (IOException e) {
            throw error("Could not create " + desc + ": " + dir);
        }
    }

    private static Path verifyDir(String desc, Path dir) {
        if (Files.isDirectory(dir)) return dir;
        throw error("Could not locate " + desc + ": " + dir);
    }

    private static Error error(String message, String...details) {
        System.err.println("ERROR: " + message);
        for (String detail: details) System.err.println(detail);
        System.exit(1);
        throw new Error();
    }

    enum EclipseOrdering implements Comparator<Path> {
        COMPARATOR;

        @Override
        public int compare(Path p1, Path p2) { return stringify(p1).compareTo(stringify(p2)); }
        private String stringify(Path p1) { return p1.toString().replace('.', '\0' ) + '\1'; }
    }
}
