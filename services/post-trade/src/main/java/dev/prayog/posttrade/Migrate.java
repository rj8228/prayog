package dev.prayog.posttrade;

import org.flywaydb.core.Flyway;

/**
 * The one-off migration job (BUILD_PLAN 16.1 #9): applies the Flyway migrations packaged in this jar, then exits. Run
 * by Compose before the service starts, from the same image, so the job and the tests use the same Flyway version.
 *
 * <p>Reads {@code PRAYOG_DB_URL}, {@code POSTGRES_USER} and {@code POSTGRES_PASSWORD}.
 */
public final class Migrate {

    private Migrate() {}

    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("PRAYOG_DB_URL", "jdbc:postgresql://localhost:5432/prayog");
        var result = Flyway.configure()
                .dataSource(url, System.getenv("POSTGRES_USER"), System.getenv("POSTGRES_PASSWORD"))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        System.out.println("migrations applied: " + result.migrationsExecuted + ", schema version "
                + (result.targetSchemaVersion == null ? "unchanged" : result.targetSchemaVersion));
    }
}
