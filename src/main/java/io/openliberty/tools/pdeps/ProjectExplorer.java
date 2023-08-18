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
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.PropertiesDefaultProvider;

import java.awt.datatransfer.StringSelection;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static java.awt.Toolkit.getDefaultToolkit;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableSet;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

@Command(
        name = "px",
        mixinStandardHelpOptions = true,
        description = "Project eXplorer - explore relationships between projects in a bnd workspace " +
                "and their corresponding projects in an eclipse workspace",
        version = "Project eXplorer 0.8",
        subcommands = {HelpCommand.class, ProjectExplorer.Focus.class}, // other subcommands are annotated methods
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

    @Command(name = "focus", description = "Indicate which projects you intend to edit. This allows more useful analysis of dependencies. " +
            "The focus subcommand will consider the dependencies of the focused projects and their direct users. ")
    static class Focus {
        public static final String KLUGE = "kluge:";
        @ParentCommand
        ProjectExplorer px;

        @Command(description = "Add the specified pattern(s) to the focus list.")
        void add(
                @Parameters(paramLabel = "patterns", arity = "1..*",  description = "The names (or patterns) of project(s) to be edited in eclipse.")
                List<String> patterns
        ) {
            var oldFocusList = getRawFocusList();
            // compute the new list
            var newFocusList = new ArrayList<>(oldFocusList);
            newFocusList.addAll(patterns);
            // write this out to file
            writeFocusList(newFocusList);
            printFocusList(newFocusList);
            summariseChanges(oldFocusList, newFocusList);
        }

        @Command(description = "Clear the focus list completely.")
        void clear() {
            writeFocusList(emptyList());
            System.out.println("Focus list cleared");
        }

        @Command(aliases = "remove", description = "Remove the specified pattern(s) from the focus list.")
        void delete(
                @Parameters(paramLabel = "patterns", arity = "1..*", description = "The names (or patterns) of project(s) no longer to be edited in eclipse.")
                List<String> patterns
        ) {
            var oldFocusList = getRawFocusList();
            // compute the new list
            var newFocusList = new ArrayList<>(oldFocusList);
            newFocusList.removeAll(patterns);
            writeFocusList(newFocusList);
            printFocusList(newFocusList);
            summariseChanges(oldFocusList, newFocusList);
        }

        @Command(aliases={"kludge"}, description = "Adds the specified project as a kluge for undetected dependencies that show up as errors in eclipse. This will NOT pull in the users of this project.")
        void kluge(
                @Parameters(description = "The name of a project to add")
                String project
        ) {
            if (!px.getBndCatalog().hasProject(project)) throw error("Unable to find project: " + project);
            var oldFocusList = getRawFocusList();
            // compute the new list
            var newFocusList = new ArrayList<>(oldFocusList);
            newFocusList.add(encodeKluge(project));
            // write this out to file
            writeFocusList(newFocusList);
            printFocusList(newFocusList);
            summariseChanges(oldFocusList, newFocusList);
        }

        @Command(description = "List focus projects.")
        void list() {
            var focusList = getRawFocusList();
            printFocusList(focusList);
            System.out.printf("%nFocus projects:%n");
            formatProjects(focusList).map("\t"::concat).forEach(System.out::println);
        }

        @Command(description = "Print the next project to add to eclipse based on the current project focus, and copy it to the clipboard.")
        void next(
                @Option(names = {"-x", "--exhaustive"}, description = "Prints all the remaining projects to be imported. Does NOT copy anything to the clipboard.")
                boolean exhaustive
        ) {
            Stream<Path> allNeededProjects = getAllRequiredProjects();
            var needed = allNeededProjects
                    .filter(p -> !px.getKnownProjects().contains(p.getFileName().toString()))
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .collect(toList());
            if (needed.isEmpty()) {
                System.err.println("Nothing more to import!");
                System.exit(1);
            }
            if (exhaustive) {
                needed.forEach(System.out::println);
            } else {
                String next = needed.get(0);
                System.out.printf("%s (%d more to go)%n", next, needed.size() - 1);
                getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(next), null);
            }
        }

        @Command(description = "Identify orphaned projects in eclipse not needed for editing the current focus projects")
        void orphans() {
            var required = getAllRequiredProjects().map(Path::getFileName).map(Path::toString).collect(toSet());
            var known = new TreeSet<>(px.getKnownProjects());
            known.removeAll(required);
            System.out.println("The following projects are no longer required for the current focus:");
            known.stream().map("\t"::concat).forEach(System.out::println);
            System.out.println("These projects can be closed, or deleted from eclipse (but not from the filesystem).");
        }

        @Command(aliases={"unkludge"}, description = "Removes the specified project from the list of kluges.")
        void unkluge(
                @Parameters(description = "The name of a project to remove")
                String project
        ) {
            var klugeProject = KLUGE + project;
            var oldFocusList = getRawFocusList();
            if (!oldFocusList.contains(klugeProject)) throw error("Unable to find kluge in list: " + project);
            // compute the new list
            var newFocusList = new ArrayList<>(oldFocusList);
            newFocusList.remove(encodeKluge(project));
            // write this out to file
            writeFocusList(newFocusList);
            printFocusList(newFocusList);
            summariseChanges(oldFocusList, newFocusList);
        }

        private void writeFocusList(List<String> newFocusList) {
            // write this out to file
            try (FileWriter fw = new FileWriter(getFocusListFile().toFile()); PrintWriter pw = new PrintWriter(fw)) {
                newFocusList.forEach(pw::println);
            } catch (IOException e) {
                throw error("Failed to open focus file for writing");
            }
        }

        private void printFocusList(List<String> rawFocusList) {
            System.out.printf("%nKluge list:%n");
            getKlugeProjects(rawFocusList).map("\t"::concat).forEach(System.out::println);
            System.out.printf("%nFocus list:%n");
            getFocusPatterns(rawFocusList).map("\t"::concat).forEach(System.out::println);
            if (rawFocusList.stream().anyMatch(s -> s.startsWith("!"))) System.out.println("N.B. When using exclusion (a pattern preceded by an exclamation mark), order is important. Inclusions and exclusions happens in list order.");
        }

        private void summariseChanges(List<String> oldFocusList, List<String> newFocusList) {
            var before = formatProjects(oldFocusList).collect(toSet());
            var after = formatProjects(newFocusList).collect(toSet());
            Set<String> union = new TreeSet<>(before);
            union.addAll(after);
            Set<String> intersection = new TreeSet<>(before);
            intersection.retainAll(after);
            Set<String> added = new TreeSet<>(after);
            added.removeAll(before);
            Set<String> deleted = new TreeSet<>(before);
            deleted.removeAll(after);
            System.out.printf("%nFocused projects:%n");
            union.stream()
                    .map(p -> (added.contains(p) ? "+" : deleted.contains(p) ? "-" : " ") + p)
                    .map("\t"::concat)
                    .forEach(System.out::println);
            System.out.printf("%d added, %d removed, %d unchanged.%n", added.size(), deleted.size(), intersection.size());
        }

        private Stream<String> formatProjects(List<String> focusList) {
            var kluges = getKlugeProjects(focusList).map(s -> s + " (kluge)");
            var focuses = px.getMatchingProjects(getFocusPatterns(focusList).collect(toList())).stream();
            return concat(kluges, focuses);
        }

        private Stream<Path> getAllRequiredProjects() {
            var focusProjects = px.getMatchingProjects(getFocusPatterns().collect(toList()));
            var users = px.getBndCatalog().getDependentProjectPaths(focusProjects).map(Path::getFileName).map(Path::toString);
            var all = concat(focusProjects.stream(), users).collect(toSet());
            var mainList = px.getBndCatalog().getRequiredProjectPaths(all).collect(toList());
            var kluges = getKlugeProjects()
                    .map(px.getBndCatalog()::getProject)
                    .map(Path::getFileName).map(Path::toString)
                    .collect(toSet());
            var klugeList = px.getBndCatalog().getRequiredProjectPaths(kluges).collect(toList());
            // need to prioritise any identified kluges and their dependencies
            // and only then consider the remaining projects
            var remainder = mainList.stream().filter(not(new HashSet<>(klugeList)::contains));
            return concat(klugeList.stream(), remainder);
        }

        private Stream<String> getKlugeProjects() { return getKlugeProjects(getRawFocusList()); }
        private Stream<String> getFocusPatterns() { return getFocusPatterns(getRawFocusList()); }
        private Stream<String> getKlugeProjects(List<String> rawFocusList) { return rawFocusList.stream().filter(this::isKluge).map(this::decodeKluge); }
        private Stream<String> getFocusPatterns(List<String> rawFocusList) { return rawFocusList.stream().filter(not(this::isKluge)); }
        private String decodeKluge(String pattern) { return pattern.substring(KLUGE.length()); }
        private String encodeKluge(String project) { return KLUGE + project; }
        private boolean isKluge(String pattern) { return pattern.startsWith(KLUGE); }
        private List<String> getRawFocusList() {
            try {
                return Files.readAllLines(getFocusListFile());
            } catch (IOException e) {
                throw error("Could not open the focus file for reading");
            }
        }

        private Path getFocusListFile() { return verifyOrCreateFile("focus list file", px.getEclipsePxDir().resolve("focus-list")); }
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
        getBndCatalog().getDependentProjectPaths(projectNames)
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
        patterns.forEach(pattern -> {
            try {
                boolean exclude = pattern.startsWith("!");
                if (exclude) pattern = pattern.substring(1);
                getBndCatalog().findProjects(pattern).map(Path::getFileName).map(Path::toString).forEach(exclude ? set::remove : set::add);
            } catch (NoSuchElementException e) {
                System.err.printf("error: no projects found matching pattern '%s'%n", pattern);
            }
        });
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
