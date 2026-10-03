package dev.prayog.contracts;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Loads schemas from the classpath. Every {@code $id} starts with {@link #ID_PREFIX}; nothing is fetched remotely. */
final class Schemas {

    static final String ID_PREFIX = "https://prayog.dev/schemas/";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(ID_PREFIX, "classpath:schemas/")));

    private Schemas() {}

    /** @param name path under schemas/ without the suffix, e.g. {@code rest/new-order-request} */
    static Schema load(String name) {
        return REGISTRY.getSchema(SchemaLocation.of(ID_PREFIX + name + ".schema.json"));
    }

    /** Raw schema JSON, for checks that compare the schema with Java types. */
    static JsonNode tree(String name) {
        return JSON.readTree(read(classpath("schemas/" + name + ".schema.json")));
    }

    /** Every schema name except common, e.g. {@code [events/exchange-event, rest/new-order-request, ...]}. */
    static List<String> names() {
        Path root = classpath("schemas");
        try (Stream<Path> files = Files.walk(root)) {
            return files.map(p -> root.relativize(p).toString())
                    .filter(p -> p.endsWith(".schema.json") && p.contains("/"))
                    .map(p -> p.substring(0, p.length() - ".schema.json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path classpath(String resource) {
        try {
            return Path.of(Schemas.class.getClassLoader().getResource(resource).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
