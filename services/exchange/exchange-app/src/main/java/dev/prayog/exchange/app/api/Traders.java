package dev.prayog.exchange.app.api;

import dev.prayog.exchange.app.security.Trader;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Builds the calling {@link Trader} from a validated token and the optional account label. */
public final class Traders {

    private Traders() {}

    public static Trader from(JwtAuthenticationToken auth, String label) {
        Set<String> roles = new HashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (name.startsWith("ROLE_")) {
                roles.add(name.substring(5));
            }
        }
        String clientId = auth.getToken().getClaimAsString("azp");
        String username = auth.getToken().getClaimAsString("preferred_username");
        return Trader.of(auth.getToken().getSubject(), username, label, clientId, roles);
    }
}
