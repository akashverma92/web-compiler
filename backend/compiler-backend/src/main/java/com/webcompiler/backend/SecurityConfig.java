package com.webcompiler.backend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class SecurityConfig {

    private final ConcurrentHashMap<String, Long> lastRequestTime = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_MS = 500; // allow max 2 requests/sec per IP
    private static final int TOO_MANY_REQUESTS = 429;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**", "/ws/**").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            .addFilterBefore((request, response, chain) -> {

                HttpServletRequest httpReq = (HttpServletRequest) request;
                HttpServletResponse httpResp = (HttpServletResponse) response;

                String path = httpReq.getRequestURI();

                // Skip WebSocket handshake requests
                if (path.startsWith("/ws/")) {
                    chain.doFilter(request, response);
                    return;
                }

                String ip = request.getRemoteAddr();
                long now = System.currentTimeMillis();
                Long last = lastRequestTime.getOrDefault(ip, 0L);

                if (now - last < MIN_INTERVAL_MS) {
                    httpResp.setContentType("application/json");
                    httpResp.setCharacterEncoding("UTF-8");
                    httpResp.setStatus(TOO_MANY_REQUESTS);
                    try {
                        httpResp.getWriter().write("{\"error\":\"Too Many Requests\",\"status\":429}");
                    } catch (IOException ignored) {}
                    return;
                }

                lastRequestTime.put(ip, now);
                chain.doFilter(request, response);

            }, org.springframework.web.filter.CorsFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
