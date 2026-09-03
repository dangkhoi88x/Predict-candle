package com.example.candles.config;

import com.example.candles.auth.JwtAuthenticationFilter;
import com.example.candles.auth.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/wallet/nonce", "/api/auth/wallet/verify",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        /*
                         * Everything an admin can do. hasRole reads the role out of the access
                         * token, which is the cheap gate; the endpoints that write re-read it
                         * from the database through AdminAccess.
                         */
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Writes to the shared Cloudinary account, so admin-only for the same
                        // reason: on this app "authenticated" means anyone who connected a wallet.
                        .requestMatchers("/api/media/**").hasRole("ADMIN")
                        // Personal totals — nothing meaningful to serve anonymously.
                        .requestMatchers("/api/stats/**").authenticated()
                        .anyRequest().permitAll())
                /*
                 * Rejections that happen in the filter chain never reach the controller advice,
                 * so without this the front end gets Spring's HTML error page for a 401/403 and
                 * its `(await res.json()).message` throws on it. Same JSON shape as everywhere else.
                 */
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Cần đăng nhập để dùng chức năng này."))
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "Tài khoản này không có quyền quản trị.")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Hand-written rather than serialized: this runs in the filter chain, where reaching
        // for a message converter buys nothing for a two-field object with no user input in it.
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
