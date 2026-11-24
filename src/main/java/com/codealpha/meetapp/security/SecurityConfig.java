package com.codealpha.meetapp.security;

import com.codealpha.meetapp.common.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Spring Security configuration:
 * - Stateless authentication with JWT Bearer tokens
 * - CSRF disabled (stateless token authentication without session cookies)
 * - Strict route authorization chain ending in anyRequest().denyAll()
 * - Security headers: HSTS, nosniff, FrameOptions DENY, strict referrer policy,
 *   strict Content-Security-Policy (CSP), and Permissions-Policy
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CorsConfigurationSource corsConfigurationSource;
    private final AppProperties appProperties;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          CorsConfigurationSource corsConfigurationSource,
                          AppProperties appProperties) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.corsConfigurationSource = corsConfigurationSource;
        this.appProperties = appProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    private String buildCspPolicy() {
        String origin = appProperties != null ? appProperties.getAllowedOrigin() : null;
        String wsOrigin = "";
        if (origin != null && !origin.isBlank()) {
            String trimmed = origin.trim();
            if (trimmed.startsWith("https://")) {
                wsOrigin = " wss://" + trimmed.substring(8);
            } else if (trimmed.startsWith("http://")) {
                wsOrigin = " ws://" + trimmed.substring(7);
            }
        }
        return "default-src 'self'; " +
                "script-src 'self'; " +
                "style-src 'self'; " +
                "img-src 'self' data: blob:; " +
                "media-src 'self' blob:; " +
                "connect-src 'self'" + wsOrigin + "; " +
                "frame-ancestors 'none'; " +
                "object-src 'none'; " +
                "base-uri 'self'; " +
                "form-action 'self'";
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF is disabled because this is a stateless REST API using Bearer tokens
                // in Authorization headers rather than session cookies, making it immune to CSRF attacks.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers(
                                "/", "/index.html", "/login.html", "/signup.html", "/dashboard.html",
                                "/prejoin.html", "/room.html", "/ended.html", "/css/**", "/js/**",
                                "/favicon.ico", "/ws"
                        ).permitAll()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true)
                        )
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                        )
                        .contentSecurityPolicy(csp -> csp.policyDirectives(buildCspPolicy()))
                        .permissionsPolicy(permissions -> permissions
                                .policy("camera=(self), microphone=(self), display-capture=(self)")
                        )
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
