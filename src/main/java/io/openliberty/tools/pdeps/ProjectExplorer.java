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

import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.awt.Toolkit.getDefaultToolkit;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;

@Command(
        name = "px",
        mixinStandardHelpOptions = true,
        description = "Project eXplorer - explore relationships between projects in a bnd workspace " +
                "and their corresponding projects in an eclipse workspace",
        version = "Project eXplorer 0.8",
        subcommands = {HelpCommand.class, Focus.class}, // other subcommands are annotated methods
        defaultValueProvider = PropertiesDefaultProvider.class
)
public class ProjectExplorer {
    @Option(names = {"-b", "--bnd-workspace"}, defaultValue = ".", description = "Location of the bnd workspace")
    Path bndWorkspace;

    @Option(names = {"-c", "--eclipse-command"}, split="\n", splitSynopsisLabel = "\\n", description = "Command to open a directory for import into eclipse")
    List<String> eclipseCommand;

    @Option(names = {"-e", "--eclipse-workspace"}, defaultValue = "../../eclipse", description = "Location of the eclipse workspace")
    Path eclipseWorkspace;

    @Option(names = {"-f", "--finish-command"}, split="\n", splitSynopsisLabel = "\\n", description = "Command to press finish on eclipse's import dialog")
    List<String> finishCommand;

    @Option(names = {"-q", "--quiet"}, description = "Suppress extraneous information. Might be useful when using in a script.")
    boolean quiet;

    @Option(names = {"-v", "--verbose"}, description = "Show more information about processing.")
    boolean verbose;

    @Option(names = {"-n", "--dry-run"}, description = "Do not run commands - just print them.")
    boolean dryRun;

    private BndCatalog catalog;

    static void copyToClipboard(String text) { getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); }
    public static void main(String...args) throws Exception {
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
            @Parameters(paramLabel = "PROJECT", arity = "1..*", description = "The project(s) whose dependencies are to be displayed.")
            List<String> projectNames) {
        var eclipseProjects = getProjectsInEclipse();
        getBndCatalog();
        var paths = catalog.getRequiredProjectPaths(projectNames)
                .filter(p -> showAll || !eclipseProjects.contains(p.getFileName().toString()))
                .map(printNames ? Path::getFileName : Path::toAbsolutePath);
        if (eclipseOrdering) paths = paths.sorted(EclipseOrdering.COMPARATOR);
        paths.forEach(System.out::println);
    }

    @Command(description = "Lists projects needed by but missing from Eclipse. Full paths are displayed, for ease of pasting into Eclipse's Import Project... dialog. ")
    void gaps() {
        var eclipseProjects = getProjectsInEclipse();
        getBndCatalog();
        catalog.getRequiredProjectPaths(eclipseProjects, true)
                .filter(p -> !eclipseProjects.contains(p.getFileName().toString()))
                .map(Path::toAbsolutePath)
                .forEach(System.out::println);
    }

    @Command(description = "show projects already known to Eclipse")
    void known() { getProjectsInEclipse().forEach(System.out::println); }

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
        var eclipseProjects = getProjectsInEclipse();
        getBndCatalog();
        var graph = catalog.getProjectAndDependencySubgraph(eclipseProjects, true);
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
        getProjectsInEclipse();
        getBndCatalog().getDependentProjectPaths(projectNames)
                .map(Path::getFileName)
                .forEach(System.out::println);
    }

    BndCatalog getBndCatalog() {
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

    Set<String> getProjectsInEclipse() {
        Path dotProjectsDir = getEclipseDotProjectsDir();
        try {
            return Files.list(dotProjectsDir)
                    .filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(not(s -> s.startsWith(".")))
                    .collect(toSet());
        } catch (IOException e) {
            throw error("Could not enumerate Eclipse projects despite finding metadata location: " + dotProjectsDir,
                    "Exception was " + e);
        }
    }

    Set<String> getMatchingProjects(List<String> patterns) {
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

    Path getEclipsePxDir() { return verifyOrCreateDir("eclipse px settings dir", getEclipseWorkspace().resolve(".px")); }
    private Path getEclipseWorkspace() { return verifyDir("eclipse workspace", eclipseWorkspace); }
    void invokeEclipse(Path path) { invokeEclipse(path.toString()); }
    void invokeEclipse(String path) {
        verbose("Invoking eclipse to import %s", path);
        // invoke eclipse
        run(requireEclipseCommand(), path);
        // optionally click finish
        Optional.ofNullable(getFinishCommand()).filter(not(List::isEmpty)).ifPresent(this::run);
    }

    private static boolean isMacOS() { return "Mac OS X".equals(System.getProperty("os.name")); }
    private void run(List<String> cmd, String...extraArgs) {
        Stream.of(extraArgs).forEach(cmd::add);
        try {
            if (dryRun) System.out.println(cmd.stream().collect(joining("' '", "'" , "'")));
            else new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (IOException e) {
            error("Error invoking command " + cmd.stream().collect(joining("' '", "'", "'")) + e.getMessage());
        } catch (InterruptedException e) {
            error("Interrupted waiting for command " + cmd.stream().collect(joining("' '", "'", "'")) + e.getMessage());
        }
    }
    private List<String> requireEclipseCommand() { return Optional.ofNullable(getEclipseCommand()).orElseThrow(() -> error("Must specify eclipse command in order to automate eclipse actions.")); }
    private List<String> getEclipseCommand() { return null != eclipseCommand ? eclipseCommand : defaultEclipseCommand(); }
    private List<String> defaultEclipseCommand() { return isMacOS() ? new ArrayList(asList("open -a Eclipse".split(" "))) : null; }
    private List<String> getFinishCommand() { return  null != finishCommand ? finishCommand : defaultFinishCommand(); }
    private List<String> defaultFinishCommand() { return isMacOS() ? asList("osascript", "-e", "tell app \"System Events\" to tell process \"Eclipse\" to click button \"Finish\" of window 1") : null; }

    static Path verifyOrCreateFile(String desc, Path file) {
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

    static void warn(String message, String... details) {
        System.err.println("WARNING: " + message);
        for (String detail: details) System.err.println(detail);
    }

    static Error error(String message, String... details) {
        System.err.println("ERROR: " + message);
        for (String detail: details) System.err.println(detail);
        System.exit(1);
        throw new Error();
    }

    void info(String msg, Object...inserts) { if (!quiet) System.out.println(String.format(msg, inserts)); }
    void info(Supplier<String> msg) { if (!quiet) System.out.println(msg.get()); }

    void verbose(String msg, Object...inserts) { if (!quiet && verbose) System.out.println(String.format(msg, inserts)); }
    void verbose(Supplier<String> msg) { if (!quiet && verbose) System.out.println(msg.get()); }

    enum EclipseOrdering implements Comparator<Path> {
        COMPARATOR;

        @Override
        public int compare(Path p1, Path p2) { return stringify(p1).compareTo(stringify(p2)); }
        private String stringify(Path p1) { return p1.toString().replace('.', '\0' ) + '\1'; }
    }
}
