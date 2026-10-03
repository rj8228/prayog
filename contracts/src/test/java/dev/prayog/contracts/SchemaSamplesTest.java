package dev.prayog.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Samples live in {@code samples/{valid,invalid}/<schema name>/*.json}. Every valid sample must pass its schema and
 * every invalid sample must fail it; each invalid sample breaks exactly one rule.
 */
class SchemaSamplesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("validSamples")
    void validSamplePasses(String sample) {
        assertThat(errors(sample)).as(sample).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSamples")
    void invalidSampleFails(String sample) {
        assertThat(errors(sample)).as(sample).isNotEmpty();
    }

    @Test
    void everySchemaHasValidAndInvalidSamples() {
        for (String schema : Schemas.names()) {
            assertThat(validSamples())
                    .as("valid samples for " + schema)
                    .anyMatch(s -> schemaOf(s).equals(schema));
            assertThat(invalidSamples())
                    .as("invalid samples for " + schema)
                    .anyMatch(s -> schemaOf(s).equals(schema));
        }
    }

    static List<String> validSamples() {
        return samples("valid");
    }

    static List<String> invalidSamples() {
        return samples("invalid");
    }

    /** {@code valid/rest/new-order-request/limit-buy.json} is checked against {@code rest/new-order-request}. */
    private static String schemaOf(String sample) {
        String withoutKind = sample.substring(sample.indexOf('/') + 1);
        return withoutKind.substring(0, withoutKind.lastIndexOf('/'));
    }

    private static List<Error> errors(String sample) {
        String json = Schemas.read(Schemas.classpath("samples/" + sample));
        return Schemas.load(schemaOf(sample)).validate(json, InputFormat.JSON);
    }

    private static List<String> samples(String kind) {
        Path root = Schemas.classpath("samples");
        try (Stream<Path> files = Files.walk(root.resolve(kind))) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> root.relativize(p).toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
