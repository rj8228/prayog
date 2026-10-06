package dev.prayog.exchange.app.security;

import dev.prayog.contracts.AccountIds;
import java.util.Set;

/**
 * Who is calling, resolved from a validated token.
 *
 * <p>The Prayog account is derived from the token's subject plus an optional account label (header
 * {@code X-Prayog-Account}, default {@code main}). One login or one bot client can therefore run several independent
 * accounts ("mm", "momentum-1"...), and nobody can name an account outside their own subject. The id is a stable hash,
 * so it needs no database and is the same on every restart and replay.
 *
 * @param username the login name ({@code preferred_username}), e.g. {@code trader1}; bots show as
 *     {@code service-account-...}
 * @param clientId the OAuth client that got the token ({@code azp}): {@code prayog-web}, {@code prayog-bot-demo}...
 */
public record Trader(
        String subject, String username, String label, long accountId, String clientId, Set<String> roles) {

    public static final String DEFAULT_LABEL = AccountIds.DEFAULT_LABEL;
    public static final String ACCOUNT_HEADER = AccountIds.HEADER;

    public boolean isBot() {
        return roles.contains("bot");
    }

    public boolean isAdmin() {
        return roles.contains("admin");
    }

    public static Trader of(String subject, String label, String clientId, Set<String> roles) {
        return of(subject, subject, label, clientId, roles);
    }

    public static Trader of(String subject, String username, String label, String clientId, Set<String> roles) {
        String l = AccountIds.label(label);
        return new Trader(
                subject, username == null ? subject : username, l, accountId(subject, l), clientId, Set.copyOf(roles));
    }

    /** See {@link AccountIds#accountId}. */
    public static long accountId(String subject, String label) {
        return AccountIds.accountId(subject, label);
    }
}
