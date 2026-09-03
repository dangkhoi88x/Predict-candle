package com.example.candles.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/**
 * Reads "Authorization: Bearer &lt;accessToken&gt;" and, when valid, authenticates the request
 * as that user id with the role the token carries.
 *
 * The principal stays a bare {@code Long} — a good deal of the codebase pattern-matches on
 * that — and the role arrives as a granted authority instead, which is what Spring Security's
 * own rules (hasRole) are written against.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtService.AccessClaims claims =
                        jwtService.parseAccessToken(header.substring("Bearer ".length()).trim());
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name()));
                var authentication =
                        new UsernamePasswordAuthenticationToken(claims.userId(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
                // No/invalid token: leave the context unauthenticated, let the endpoint's
                // own access rule decide whether that's acceptable.
            }
        }
        filterChain.doFilter(request, response);
    }
}
