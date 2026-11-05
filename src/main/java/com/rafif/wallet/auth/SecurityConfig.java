package com.rafif.wallet.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

    /** BCrypt with its default cost: salted, slow on purpose, and the salt is part of the stored hash. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Every request needs a valid access token, except the few listed as public below.
     * The token is checked by the {@code JwtDecoder} from {@link JwtConfig} (signature, expiry, issuer).
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandlers errorHandlers) throws Exception {
        http
                // CSRF protects browser sessions that use cookies. We use neither (the token is sent explicitly in a
                // header), so there is nothing for an attacker's web page to ride on.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // You cannot have a token before you have logged in.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // Health checks and the metrics scrape. Do not expose /actuator publicly (revisit in M5).
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        // API documentation.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Spring's internal error page; without this, an error would be turned into a 401.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())            // use the JwtDecoder bean
                        .authenticationEntryPoint(errorHandlers)   // a token was sent but is bad -> 401
                        .accessDeniedHandler(errorHandlers))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandlers)   // no token at all -> 401
                        .accessDeniedHandler(errorHandlers));      // logged in but not allowed -> 403
        return http.build();
    }
}
