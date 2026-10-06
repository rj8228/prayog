package dev.prayog.exchange.app.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Who may call what. Tokens come from Keycloak (realm {@code prayog}); the exchange checks them itself, offline, with
 * Keycloak's public keys: signature, expiry, issuer and audience (ADR 0008, 0009).
 *
 * <ul>
 *   <li>Public: health, metrics, instruments, market data (REST and WebSocket).
 *   <li>{@code trader} or {@code bot}: orders, own account, private feed.
 *   <li>{@code ops} (or {@code admin}): session, clock, kill switch, status.
 *   <li>{@code admin}: the admin console API (self-test, accounts, simulation control, journal replay).
 *   <li>{@code ops} or {@code admin}: also the simulation controls (read and change).
 *   <li>{@code bot}: read the simulation controls.
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        // Browsers can't set headers on a WebSocket handshake, so the private feed may pass ?access_token=...
        ServerBearerTokenAuthenticationConverter bearer = new ServerBearerTokenAuthenticationConverter();
        bearer.setAllowUriQueryParameter(true);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable) // stateless bearer tokens, no cookies
                .authorizeExchange(ex -> ex.pathMatchers(
                                "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/instruments", "/api/v1/market/**", "/api/v1/session")
                        .permitAll()
                        .pathMatchers("/api/v1/ws/market")
                        .permitAll()
                        .pathMatchers("/api/v1/ops/**")
                        .hasAnyRole("ops", "admin")
                        // The ops page (S19) steers the simulated traders too: scenario, pause, news.
                        .pathMatchers("/api/v1/admin/simulation")
                        .hasAnyRole("ops", "admin")
                        .pathMatchers("/api/v1/admin/**")
                        .hasRole("admin")
                        .pathMatchers(HttpMethod.GET, "/api/v1/simulation")
                        .hasAnyRole("bot", "ops", "admin")
                        .pathMatchers("/api/v1/**")
                        .hasAnyRole("trader", "bot")
                        .anyExchange()
                        .denyAll())
                .oauth2ResourceServer(o -> o.bearerTokenConverter(bearer)
                        .jwt(j -> j.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(roles()))))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${prayog.security.issuer}") String issuer,
            @Value("${prayog.security.audience}") String audience) {
        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceCheck =
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "token is not for audience " + audience, null));
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceCheck));
        return decoder;
    }

    /** Keycloak puts realm roles in {@code realm_access.roles}; Spring wants {@code ROLE_x} authorities. */
    static JwtAuthenticationConverter roles() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRoles);
        return converter;
    }

    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object access = jwt.getClaims().get("realm_access");
        if (access instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return authorities;
    }
}
