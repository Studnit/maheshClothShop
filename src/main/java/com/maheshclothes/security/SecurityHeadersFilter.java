package com.maheshclothes.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Content-Security-Policy: scripts only from this site (no inline scripts), no third-party CDNs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP = "default-src 'self'; "
            + "img-src 'self' data: blob:; "
            + "style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; "
            + "font-src 'self' data:; "
            + "connect-src 'self'; "
            + "frame-ancestors 'self'; base-uri 'self'; form-action 'self'";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        // Swagger UI ships its own inline bootstrap script; it is owner-only and off in production.
        return p.startsWith("/swagger-ui") || p.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("Content-Security-Policy", CSP);
        res.setHeader("Permissions-Policy", "camera=(self), microphone=(), geolocation=()");
        chain.doFilter(req, res);
    }
}
