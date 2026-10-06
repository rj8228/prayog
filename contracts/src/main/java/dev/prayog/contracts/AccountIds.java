package dev.prayog.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * How a login (token subject) plus an account label becomes a Prayog account id (ADR 0009). Shared by every service
 * that must agree on it: the exchange assigns orders to the id, post-trade shows a caller their own positions.
 */
public final class AccountIds {

    /** The label used when a request names none. */
    public static final String DEFAULT_LABEL = "main";

    /** The request header that picks one of a login's accounts. */
    public static final String HEADER = "X-Prayog-Account";

    private static final Pattern LABEL = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private AccountIds() {}

    /** {@code label}, or the default for null/blank; throws if it is not 1-32 of a-z, 0-9 and '-'. */
    public static String label(String label) {
        String l = label == null || label.isBlank() ? DEFAULT_LABEL : label;
        if (!LABEL.matcher(l).matches()) {
            throw new IllegalArgumentException(
                    "account label must be 1-32 characters of a-z, 0-9 and '-', starting with a letter or digit");
        }
        return l;
    }

    /** First 63 bits of SHA-256(subject + "/" + label); never 0. */
    public static long accountId(String subject, String label) {
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
