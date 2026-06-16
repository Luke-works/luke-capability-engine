package com.luke.capability.access;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Guards the server-to-server routes under {@code /api/internal/**} (e.g. the
 * process-triggered email endpoint) with the shared internal secret. Callers
 * present it as {@code X-Internal-Key}; it must match {@code luke.internal.shared-secret}
 * — the SAME secret core-engine uses on its own internal hops (see ProcessStarter).
 *
 * <p>Enforced only when the secret is configured; otherwise the filter passes
 * through (local dev / Postman), mirroring {@link OperatorAuthFilter} and
 * {@link GatewayAuthFilter}.
 */
@Configuration
public class InternalAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    @Bean
    public FilterRegistrationBean<Filter> internalAuthFilterRegistration(
            @Value("${luke.internal.shared-secret:}") String sharedSecret) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl(sharedSecret));
        reg.addUrlPatterns("/api/internal/*");
        reg.setName("internalAuthFilter");
        reg.setOrder(0); // before the gateway/capability filters; these routes skip both
        return reg;
    }

    private static class Impl implements Filter {
        private final String expected; // the shared secret, or null when unconfigured

        Impl(String sharedSecret) {
            if (sharedSecret != null && !sharedSecret.isBlank()) {
                this.expected = sharedSecret;
                log.info("InternalAuthFilter: enabled — /api/internal/** requires X-Internal-Key");
            } else {
                this.expected = null;
                log.warn("InternalAuthFilter: DISABLED — /api/internal/** is unauthenticated; "
                        + "set LUKE_INTERNAL_SHARED_SECRET to enforce");
            }
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;

            if (expected == null || "OPTIONS".equalsIgnoreCase(req.getMethod())) {
                chain.doFilter(request, response); // dev / preflight
                return;
            }

            String key = req.getHeader("X-Internal-Key");
            if (key == null || !constantTimeEquals(key, expected)) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Internal shared secret required\"}");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean constantTimeEquals(String a, String b) {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }
    }
}
