package dev.prayog.exchange.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who is calling, resolved from a validated token.
 *
 * <p>The Prayog account is derived from the token's subject plus an optional account label (header
 * {@code X-Prayog-Account}, default {@code main}). One login or one bot client can therefore run several independent
 * accounts ("mm", "momentum-1"...), and nobody can name an account outside their own subject. The id is a stable hash,
 * so it needs no database and is the same on every restart and replay.
 *
 * @param clientId the OAuth client that got the token ({@code azp}): {@code prayog-web}, {@code prayog-bot-demo}...
 */
public record Trader(String subject, String label, long accountId, String clientId, Set<String> roles) {

    public static final String DEFAULT_LABEL = "main";
    public static final String ACCOUNT_HEADER = "X-Prayog-Account";
    private static final Pattern LABEL = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    public boolean isBot() {
        return roles.contains("bot");
    }

    public static Trader of(String subject, String label, String clientId, Set<String> roles) {
        String l = label == null || label.isBlank() ? DEFAULT_LABEL : label;
        if (!LABEL.matcher(l).matches()) {
            throw new IllegalArgumentException(
                    "account label must be 1-32 characters of a-z, 0-9 and '-', starting with a letter or digit");
        }
        return new Trader(subject, l, accountId(subject, l), clientId, Set.copyOf(roles));
    }

    /** First 63 bits of SHA-256(subject + "/" + label); never 0. */
    static long accountId(String subject, String label) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((subject + "/" + label).getBytes(StandardCharsets.UTF_8));
            long id = 0;
            for (int i = 0; i < 8; i++) {
                id = (id << 8) | (hash[i] & 0xFF);
            }
            id &= Long.MAX_VALUE;
            return id == 0 ? 1 : id;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }
}
