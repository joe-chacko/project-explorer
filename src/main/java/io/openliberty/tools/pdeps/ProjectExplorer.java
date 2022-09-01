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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;

@Command(
        name = "px",
        mixinStandardHelpOptions = true,
        description = "Project eXplorer",
        version = "Project eXplorer 0.7",
        subcommands = HelpCommand.class, // other subcommands are annotated methods
        defaultValueProvider = PropertiesDefaultProvider.class
)
public class ProjectExplorer {
    public static final String ECLIPSE_CORE_RESOURCES_PROJECTS = ".metadata/.plugins/org.eclipse.core.resources/.projects";

    @Option(names = {"-b", "--bnd-workspace"}, defaultValue = ".", description = "Location of the bnd workspace (default=.)")
    Path bndWorkspace;

    @Option(names = {"-e", "--eclipse-workspace"}, defaultValue = "../../eclipse", description = "Location of the bnd workspace (default=.)")
    Path eclipseWorkspace;
    private BndCatalog catalog;
    private Set<String> knownProjects;

    public static void main(String...args) {
        ProjectExplorer pdeps = new ProjectExplorer();
        CommandLine commandLine = new CommandLine(pdeps);
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Command(name = "deps", description = "show dependencies of specified project(s)")
    void deps(
            @Option(names = {"-a", "--show-all"}, description = "show projects already in the eclipse workspace")
            boolean showAll,
            @Parameters(arity = "1..*", description = "project(s) whose dependencies are to be displayed")
            List<String> projectNames
    ) {
        getKnownProjects();
        getBndCatalog();
        catalog.getRequiredProjectPaths(projectNames)
                .filter(p -> showAll || !knownProjects.contains(p.getFileName().toString()))
                .map(Path::toAbsolutePath)
                .forEach(System.out::println);
        }


    @Command(name = "known", description = "show projects already known to Eclipse")
    void known() {
        getKnownProjects();
        knownProjects.forEach(System.out::println);
    }

    @Command(name = "gaps", description = "show missing dependencies of known projects")
    void gaps() {
        getKnownProjects();
        getBndCatalog();
        catalog.getRequiredProjectPaths(knownProjects, true)
                .filter(p -> !knownProjects.contains(p.getFileName().toString()))
                .map(Path::toAbsolutePath)
                .forEach(System.out::println);
    }

    @Command(name = "roots", description = "show known projects that are not required by any other projects")
    void roots() {
        getKnownProjects();
        getBndCatalog();
        var graph = catalog.getProjectandDependencySubgraph(knownProjects, true);
        graph.vertexSet().stream()
                .filter(p -> graph.inDegreeOf(p) == 0)
                .map(p -> p.name)
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

    Set<String> getKnownProjects() {
        if (this.knownProjects == null) {
            if (!!!Files.isDirectory(eclipseWorkspace))
                throw error("Could not locate eclipse workspace: " + eclipseWorkspace);
            final Path dotProjectsDir = eclipseWorkspace.resolve(ECLIPSE_CORE_RESOURCES_PROJECTS);
            if (!!!Files.isDirectory(dotProjectsDir))
                throw error("Could not locate .projects dir: " + dotProjectsDir,
                        "Please fix this tool's broken logic and submit a GitHub pull request");
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

    private Error error(String message, String...details) {
        System.err.println("ERROR: " + message);
        for (String detail: details) System.err.println(detail);
        System.exit(1);
        throw new Error();
    }
}
