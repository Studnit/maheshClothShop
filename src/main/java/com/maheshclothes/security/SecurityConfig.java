package com.maheshclothes.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Stateless API clients (mobile app later, Swagger) send HTTP Basic; they have no ambient cookie,
        // so CSRF does not apply to them. Browser sessions always need the CSRF token.
        RequestMatcher basicAuthHeader = req -> {
            String h = req.getHeader("Authorization");
            return h != null && h.regionMatches(true, 0, "Basic ", 0, 6);
        };
        RequestMatcher apiRequest = (HttpServletRequest req) -> req.getRequestURI().startsWith("/api/");

        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/setup", "/error", "/error/**", "/manifest.webmanifest", "/sw.js",
                                "/offline.html", "/favicon.ico", "/css/**", "/js/**", "/vendor/**", "/img/**",
                                "/icons/**", "/i/**", "/webhooks/**", "/actuator/health/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").hasRole("OWNER")
                        .requestMatchers("/actuator/**").hasRole("OWNER")
                        .anyRequest().authenticated())
                .formLogin(f -> f
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .failureHandler((req, res, ex) -> res.sendRedirect(
                                req.getContextPath() + (ex instanceof LockedException ? "/login?locked" : "/login?error"))))
                .httpBasic(Customizer.withDefaults())
                .logout(l -> l
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("MCS_SESSION"))
                .csrf(c -> c.ignoringRequestMatchers(basicAuthHeader, req -> req.getRequestURI().startsWith("/webhooks/")))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), apiRequest)
                        .accessDeniedHandler((req, res, ex) -> {
                            if (req.getRequestURI().startsWith("/api/")) {
                                res.setStatus(HttpStatus.FORBIDDEN.value());
                                res.setContentType("application/json");
                                res.getWriter().write("{\"status\":403,\"title\":\"Forbidden\"}");
                            } else {
                                res.sendRedirect(req.getContextPath() + "/error/403");
                            }
                        }))
                .headers(h -> h
                        .frameOptions(f -> f.sameOrigin())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }
}
