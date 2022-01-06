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

package io.openliberty.tools.bnddeps;

import io.openliberty.tools.bnddeps.Catalog.Project;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;

public class Main {
    private static final Options OPTIONS = new Options()
            .addOption("a", "all", false, "show projects already in the eclipse workspace")
            .addOption("h", "help", false, "print a help message")
            .addOption("b", "bnd-workspace", true, "specify location of the bnd workspace (default=.)")
            .addOption("e", "eclipse-workspace", true, "specify location of the eclipse workspace (default=../../eclipse");
    public static final String ECLIPSE_CORE_RESOURCES_PROJECTS = ".metadata/.plugins/org.eclipse.core.resources/.projects";
    private static boolean errorHappened;

    private static void printHelp() {
        HelpFormatter hf = new HelpFormatter();
        hf.printHelp("bnddeps [options] project...", OPTIONS);
    }

    private static CommandLine parse(String...args) {
        try {
            final CommandLine cmdLine = new DefaultParser().parse(OPTIONS, args);
            if (cmdLine.hasOption("h") || cmdLine.getArgList().isEmpty()) {
                printHelp();
                System.exit(0);
            }
            return cmdLine;
        } catch (ParseException e) {
            System.err.println("Encountered error parsing options: " + e.getMessage());
            printHelp();
            System.exit(1);
            throw new Error();
        }
    }

    public static void main(String...args) {
        CommandLine cmdLine = parse(args);
        Path bndWorkspace = Paths.get(cmdLine.getOptionValue("b", "."));
        Path eclipseWorkspace = Paths.get(cmdLine.getOptionValue("e", "../../eclipse"));

        try {
            System.out.println("================================================================================");
            if (!!!Files.isDirectory(bndWorkspace)) error("Could not locate bnd workspace: " + bndWorkspace);
            else {
                final Catalog catalog = new Catalog(bndWorkspace, getKnownEclipseProjects(eclipseWorkspace));
                catalog.showAllProjects(cmdLine.hasOption('a'));
                cmdLine.getArgList().stream()
                        .map(catalog::getCanonical)
                        .forEach(Project::printInTopologicalOrder);
            }
        } catch (IOException e) {
            error(e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("================================================================================");
        }
        System.exit(errorHappened ? -1 : 0);
    }


    static Set<String> getKnownEclipseProjects(Path eclipseWorkspace) {
        if (!!!Files.isDirectory(eclipseWorkspace)) {
            error("Could not locate eclipse workspace: " + eclipseWorkspace,
                    "To remove this error message, do not set the eclipse.workspace property");
            return emptySet();
        }
        final Path dotProjectsDir = eclipseWorkspace.resolve(ECLIPSE_CORE_RESOURCES_PROJECTS);
        if (!!!Files.isDirectory(dotProjectsDir)) {
            error("Could not locate .projects dir: " + dotProjectsDir,
                    "Please fix this tool's broken logic and submit a GitHub pull request");
            return emptySet();
        }
        try {
            return unmodifiableSet(Files.list(dotProjectsDir)
                    .filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .collect(toSet()));
        } catch (IOException e) {
            error("Could not enumerate Eclipse projects despite finding metadata location: " + dotProjectsDir,
                    "Exception was " + e);
            e.printStackTrace();
            return emptySet();
        }
    }

    private static void error(String message, String...details) {
        errorHappened = true;
        System.err.println("ERROR: " + message);
        for (String detail: details) System.err.println(detail);
    }
}
