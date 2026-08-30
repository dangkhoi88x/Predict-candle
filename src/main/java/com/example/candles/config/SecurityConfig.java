package com.example.candles.config;

import com.example.candles.auth.JwtAuthenticationFilter;
import com.example.candles.auth.JwtService;
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
                        // Writes to the shared Cloudinary account. Authentication is the
                        // outer gate; MediaController additionally checks the caller's
                        // wallet against candles.media.admin-wallets.
                        .requestMatchers("/api/media/**").authenticated()
                        // Personal totals — nothing meaningful to serve anonymously.
                        .requestMatchers("/api/stats/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
