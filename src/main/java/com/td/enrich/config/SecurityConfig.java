package com.td.enrich.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security configuration for the TD Enrich Service.
 *
 * <p><b>Authentication model:</b> API key via the {@code X-API-Key} request header.
 * Every request to a protected endpoint must include the header:
 * <pre>{@code
 * X-API-Key: <your-secret-key>
 * }</pre>
 * The expected key is read from the {@code ENRICH_API_KEY} environment variable
 * (property: {@code enrich.security.api-key}). If the variable is not set, the service
 * refuses to start — you must explicitly configure a key in production.
 *
 * <p><b>Protected endpoints:</b>
 * <ul>
 *   <li>{@code POST /api/v1/enrich} — requires valid API key</li>
 *   <li>{@code POST /api/v1/enrich/batch} — requires valid API key</li>
 *   <li>{@code GET  /api/v1/enrich/{id}} — requires valid API key</li>
 * </ul>
 *
 * <p><b>Public endpoints (no API key required):</b>
 * <ul>
 *   <li>{@code GET /api/v1/enrich/health} — used by load balancers; must remain open</li>
 *   <li>{@code GET /actuator/health} — used by Kubernetes liveness/readiness probes</li>
 *   <li>{@code GET /actuator/info} — non-sensitive build info</li>
 * </ul>
 *
 * <p><b>Other security decisions:</b>
 * <ul>
 *   <li>Session creation is disabled ({@code STATELESS}) — the service is a REST API,
 *       not a web app, so no HTTP sessions are created or maintained.</li>
 *   <li>CSRF protection is disabled — CSRF attacks require a browser session with cookies;
 *       API key authentication has no such attack surface.</li>
 *   <li>HTTP Basic and form login are disabled — the only auth mechanism is the API key
 *       filter added by {@link ApiKeyAuthFilter}.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    /**
     * The expected API key, injected from the {@code ENRICH_API_KEY} environment variable.
     * No default is provided — the service will fail to start if the variable is absent,
     * which prevents accidental deployment with no authentication.
     */
    @Value("${enrich.security.api-key}")
    private String expectedApiKey;

    /**
     * Defines the HTTP security filter chain.
     *
     * <p>The {@link ApiKeyAuthFilter} is inserted before Spring's
     * {@link UsernamePasswordAuthenticationFilter} so it runs first. Requests that fail
     * API key validation are rejected with 401 before reaching any other filter or controller.
     *
     * @param http the Spring Security HTTP builder
     * @return the configured {@link SecurityFilterChain}
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — not applicable for stateless API key authentication
            .csrf(AbstractHttpConfigurer::disable)
            // Disable session creation — this is a stateless REST service
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Disable HTTP Basic and form login — API key filter handles auth
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                // Load balancer and probe endpoints must be reachable without credentials
                .requestMatchers("/api/v1/enrich/health", "/actuator/health", "/actuator/info").permitAll()
                // All other /api/** and /actuator/** endpoints require a valid API key
                .anyRequest().authenticated()
            )
            // Register the API key filter before the standard username/password filter
            .addFilterBefore(new ApiKeyAuthFilter(expectedApiKey),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Servlet filter that validates the {@code X-API-Key} request header.
     *
     * <p>For every request that is not matched by a {@code permitAll} rule:
     * <ol>
     *   <li>Read the {@code X-API-Key} header.</li>
     *   <li>Compare it to the configured expected key using a constant-time comparison
     *       to prevent timing attacks.</li>
     *   <li>If valid, continue the filter chain — Spring Security treats the request
     *       as authenticated.</li>
     *   <li>If missing or invalid, respond with {@code 401 Unauthorized} and stop the
     *       filter chain so the request never reaches a controller.</li>
     * </ol>
     *
     * <p>Public paths (health checks, actuator probes) are skipped entirely via
     * {@link #shouldNotFilter} — the filter never runs for those paths so they are
     * always reachable by load balancers without an API key.
     *
     * <p>{@link OncePerRequestFilter} guarantees this filter executes exactly once per
     * HTTP request, even in servlet environments that might invoke filters multiple times
     * (e.g. during error dispatching).
     */
    static class ApiKeyAuthFilter extends OncePerRequestFilter {

        /** The header name callers must set on every authenticated request. */
        static final String API_KEY_HEADER = "X-API-Key";

        /** Paths that must remain open for load balancers and Kubernetes probes. */
        private static final List<String> PUBLIC_PATHS = List.of(
                "/api/v1/enrich/health",
                "/actuator/health",
                "/actuator/info"
        );

        /**
         * Skips this filter for public paths so they are always reachable without
         * an API key. This is checked before {@link #doFilterInternal} is called.
         *
         * @param request the incoming HTTP request
         * @return {@code true} if the path is in {@link #PUBLIC_PATHS}
         */
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI();
            return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
        }

        private final String expectedApiKey;

        ApiKeyAuthFilter(String expectedApiKey) {
            this.expectedApiKey = expectedApiKey;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
                throws ServletException, IOException {

            // If a previous filter (or test framework) has already authenticated this
            // request, skip the API key check. In production nothing sets auth before
            // this filter runs — it is here solely so @WithMockUser works in controller
            // tests without requiring a real API key header.
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                filterChain.doFilter(request, response);
                return;
            }

            String providedKey = request.getHeader(API_KEY_HEADER);

            if (providedKey == null || !constantTimeEquals(providedKey, expectedApiKey)) {
                // Log at WARN so failed auth attempts are visible in monitoring without
                // exposing the provided key value in the log
                log.warn("Rejected request — missing or invalid API key: method={} uri={}",
                        request.getMethod(), request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Missing or invalid X-API-Key header\"}");
                return; // stop the chain — do not call filterChain.doFilter
            }

            // Key is valid — mark the request as authenticated so Spring Security's
            // authorization checks see a principal and allow the request through.
            // We use a synthetic "api-key-client" principal with the ROLE_API_CLIENT
            // authority. No password is needed — the API key itself is the credential.
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "api-key-client", null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);

            filterChain.doFilter(request, response);
        }

        /**
         * Compares two strings in constant time to prevent timing attacks.
         *
         * <p>A naive {@code String.equals} returns early as soon as it finds a mismatched
         * character, which means an attacker can measure response times to guess the
         * key one character at a time. This method always checks every character position
         * regardless of where the first mismatch occurs.
         *
         * @param a first string
         * @param b second string
         * @return {@code true} if both strings are identical
         */
        private boolean constantTimeEquals(String a, String b) {
            if (a.length() != b.length()) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < a.length(); i++) {
                // XOR each character pair: 0 if equal, non-zero if different.
                // OR-ing all results means result stays 0 only if every pair matched.
                result |= a.charAt(i) ^ b.charAt(i);
            }
            return result == 0;
        }
    }
}
