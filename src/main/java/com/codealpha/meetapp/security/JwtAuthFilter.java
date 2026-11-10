package com.codealpha.meetapp.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Filter inspecting Bearer tokens on incoming requests and establishing SecurityContext authentication.
 * Never logs raw tokens, passwords, or the Authorization header.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            Optional<Claims> claimsOpt = jwtService.parseAndValidateToken(token);

            if (claimsOpt.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
                Claims claims = claimsOpt.get();
                try {
                    Long userId = Long.parseLong(claims.getSubject());
                    String email = claims.get("email", String.class);

                    UserPrincipal principal = new UserPrincipal(userId, email);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (NumberFormatException ignored) {
                    // Invalid subject format, proceed without setting authentication
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
