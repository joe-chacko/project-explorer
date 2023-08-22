package io.openliberty.tools.pdeps;

import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.openliberty.tools.pdeps.ProjectExplorer.error;
import static io.openliberty.tools.pdeps.ProjectExplorer.verifyOrCreateFile;
import static java.util.Collections.emptyList;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

@CommandLine.Command(
        name = "focus",
        description = "Indicate which projects you intend to edit. This allows more useful analysis of dependencies. The focus subcommand will consider the dependencies of the focused projects and their direct users. ",
        subcommands = CommandLine.HelpCommand.class
)
class Focus {

    static final String KLUGE = "kluge:";
    @CommandLine.ParentCommand
    ProjectExplorer px;

    @CommandLine.Command(description = "Add the specified pattern(s) to the focus list.")
    void add(
            @CommandLine.Parameters(paramLabel = "PATTERN", arity = "1..*", description = "The names (or patterns) of project(s) to be edited in eclipse.")
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

    static class MultiAction {
        static class Auto {
            static class Waiter {
                @CommandLine.Option(names = {"-d", "--delay"}, required = true, paramLabel = "WAIT IN MILLISECONDS",
                        description = "How long to wait (in milliseconds) after invoking eclipse command. If not specified, wait for ")
                Optional<Integer> delay;
                @CommandLine.Option(names = {"-p", "--pause"}, required = true)
                boolean pause;
            }

            @CommandLine.Option(names = {"-a", "--auto"}, required = true)
            boolean auto;
            @CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1")
            MultiAction.Auto.Waiter wait;
        }

        @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
        MultiAction.Auto auto;
        @CommandLine.Option(names = {"-c", "--copy"}, required = true)
        boolean copy;
    }

    @CommandLine.Command(description = "Work with next tranche of projects whose dependencies are satisfied.")
    void batch(
            @CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1")
            MultiAction action
    ) {
        var leaves = getLeafDependencies().collect(toCollection(LinkedList::new));
        // set up the action for each leaf
        Consumer<String> print = System.out::println;
        Consumer<String> exec = null == action ? print : print.andThen(action.copy ? ProjectExplorer::copyToClipboard : px::invokeEclipse);

        var first = leaves.pollFirst();
        exec.accept(first);

        leaves.forEach(leaf -> {
            // perform the wait, if specified
            Optional.ofNullable(action).map(a -> a.auto).map(a -> a.wait).flatMap(w -> w.delay).ifPresent(t -> {
                try {
                    Thread.sleep(t);
                } catch (InterruptedException e) {
                }
            });
            Optional.ofNullable(action).map(a -> a.auto).map(a -> a.wait).filter(w -> w.pause).ifPresent(w -> {
                System.out.println("Press return to continue");
                new Scanner(System.in).nextLine();
            });
            // process the next project
            exec.accept(leaf);
        });
    }

    @CommandLine.Command(description = "Clear the focus list completely.")
    void clear() {
        writeFocusList(emptyList());
        System.out.println("Focus list cleared");
    }

    @CommandLine.Command(description = "Print all missing dependencies for current focus.")
    void deps(
            @CommandLine.Option(names = {"-c", "--count"}, description = "Show a count of the remaining dependencies")
            boolean count
    ) {
        var paths = getAllRequiredProjects()
                .filter(p -> !px.getKnownProjects().contains(p.getFileName().toString()));
        if (count) System.out.println(paths.count());
        else paths
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .forEach(System.out::println);
    }

    @CommandLine.Command(description = "Remove the specified pattern(s) from the focus list.")
    void remove(
            @CommandLine.Parameters(paramLabel = "PATTERN", arity = "1..*", description = "The names (or patterns) of project(s) no longer to be edited in eclipse.")
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

    @CommandLine.Command(aliases = {"kludge"}, description = {
            "Add a focus project to resolve errors in eclipse.",
            "The specified project and its dependencies will be prioritised over other focus projects. This will NOT pull in the users of this project."})
    void kluge(
            @CommandLine.Parameters(paramLabel = "PROJECT", description = "The name of a project to add")
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

    @CommandLine.Command(description = "List focus projects.")
    void list() {
        var focusList = getRawFocusList();
        printFocusList(focusList);
        System.out.printf("%nFocus projects:%n");
        formatProjects(focusList).map("\t"::concat).forEach(System.out::println);
    }

    static class SingleAction {
        @CommandLine.Option(names = {"-a", "--auto"}, required = true)
        boolean auto;
        @CommandLine.Option(names = {"-c", "--copy"}, required = true)
        boolean copy;
    }

    @CommandLine.Command(
            description = "Print the next project to add to eclipse based on the current project focus, and copy it to the clipboard.",
            defaultValueProvider = CommandLine.PropertiesDefaultProvider.class
    )
        // next (prints one project)
        // next (--copy|--auto) (prints one project and copies to clipboard)
        // next --auto (requires eclipse-command, uses finish-command if supplied, prints one project, imports into eclipse, optionally clicks finish)
        // batch (prints set of projects with satisfied dependencies)
        // batch --copy (prints one project at a time, copies to clipboard, waits for enter key)
        // batch (--copy|--auto [--delay <time in ms> | --pause]) (prints one project at a time, imports into eclipse, optionally clicks finish, waits for time or enter key as specified)
    void next(
            @CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1") SingleAction action
    ) {
        var next = getLeafDependencies().findFirst().get();
        System.out.println(next);
        if (null == action) return;
        if (action.copy) ProjectExplorer.copyToClipboard(next);
        if (action.auto) px.invokeEclipse(next);

    }

    private Stream<String> getLeafDependencies() {
        var projects = getKlugeProjects().collect(toSet());
        var leaves = px.getBndCatalog().getLeafProjects(projects, px.getKnownProjects()).collect(toList());
        if (leaves.isEmpty()) { // no kluge deps, so move onto remaining deps
            projects = getAllRequiredProjects().map(Path::getFileName).map(Path::toString).collect(toSet());
            leaves = px.getBndCatalog().getLeafProjects(projects, px.getKnownProjects()).collect(toList());
            if (leaves.isEmpty()) throw error("Nothing to import!");
        }
        return leaves.stream().map(Path::toAbsolutePath).map(Path::toString);
    }


    @CommandLine.Command(description = "Identify orphaned projects in eclipse not needed for editing the current focus projects")
    void orphans() {
        var required = getAllRequiredProjects().map(Path::getFileName).map(Path::toString).collect(toSet());
        var known = new TreeSet<>(px.getKnownProjects());
        known.removeAll(required);
        System.out.println("The following projects are no longer required for the current focus:");
        known.stream().map("\t"::concat).forEach(System.out::println);
        System.out.println("These projects can be closed, or deleted from eclipse (but not from the filesystem).");
    }

    @CommandLine.Command(aliases = {"unkludge"}, description = "Removes the specified project from the list of kluges.")
    void unkluge(
            @CommandLine.Parameters(description = "The name of a project to remove")
            String project
    ) {
        var klugeProject = KLUGE + project;
        var oldFocusList = getRawFocusList();
        if (!oldFocusList.contains(klugeProject))
            throw error("Unable to find kluge in list: " + project);
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
        if (rawFocusList.stream().anyMatch(s -> s.startsWith("!")))
            System.out.println("N.B. When using exclusion (a pattern preceded by an exclamation mark), order is important. Inclusions and exclusions happens in list order.");
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
        var kluges = getKlugeProjects().collect(toSet());
        var klugeList = px.getBndCatalog().getRequiredProjectPaths(kluges).collect(toList());
        // need to prioritise any identified kluges and their dependencies
        // and only then consider the remaining projects
        var remainder = mainList.stream().filter(not(new HashSet<>(klugeList)::contains));
        return concat(klugeList.stream(), remainder);
    }

    private Stream<String> getKlugeProjects() {
        return getKlugeProjects(getRawFocusList());
    }

    private Stream<String> getFocusPatterns() {
        return getFocusPatterns(getRawFocusList());
    }

    private Stream<String> getKlugeProjects(List<String> rawFocusList) {
        return rawFocusList.stream().filter(this::isKluge).map(this::decodeKluge).filter(px.getBndCatalog()::hasProject);
    }

    private Stream<String> getFocusPatterns(List<String> rawFocusList) {
        return rawFocusList.stream().filter(not(this::isKluge));
    }

    private String decodeKluge(String pattern) {
        return pattern.substring(KLUGE.length());
    }

    private String encodeKluge(String project) {
        return KLUGE + project;
    }

    private boolean isKluge(String pattern) {
        return pattern.startsWith(KLUGE);
    }

    private List<String> getRawFocusList() {
        try {
            return Files.readAllLines(getFocusListFile());
        } catch (IOException e) {
            throw error("Could not open the focus file for reading");
        }
    }

    private Path getFocusListFile() {
        return verifyOrCreateFile("focus list file", px.getEclipsePxDir().resolve("focus-list"));
    }

}
