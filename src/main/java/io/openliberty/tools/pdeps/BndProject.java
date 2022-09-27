package io.openliberty.tools.pdeps;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toUnmodifiableList;
import static org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME;

final class BndProject {
    final Path root;
    final String name;
    final String symbolicName;
    final List<String> dependencies;

    BndProject(Path root) {
        this.root = root;
        this.name = root.getFileName().toString();
        Properties props = getBndProps(root);
        this.symbolicName = Optional.of(props)
                .map(p -> p.getProperty(BUNDLE_SYMBOLICNAME))
                .map(val -> val.replaceFirst(";.*", ""))
                .map(String::trim)
                .orElse(null);
        List<String> deps = new ArrayList<>();
        deps.addAll(getPathProp(props, "-buildpath"));
        deps.addAll(getPathProp(props, "-testpath"));
        this.dependencies = unmodifiableList(deps);
    }

    private static Properties getBndProps(Path root) {
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
    public String toString() {
        return name;
    }
}
