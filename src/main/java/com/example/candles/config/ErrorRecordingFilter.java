package com.example.candles.config;

import com.example.candles.service.RecentErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Notes an exception nothing handled — the kind that becomes a bare 500 and a stack trace — in
 * {@link RecentErrors}, then lets it carry on exactly as before. Outermost, so it sees what escapes
 * Spring Security's chain as well as the controllers.
 *
 * Handled failures do not pass through here as exceptions: an upstream failure is turned into a
 * 502 by {@code GlobalExceptionHandler}, which records it itself with the reason it already knows.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorRecordingFilter extends OncePerRequestFilter {

    private final RecentErrors recentErrors;

    public ErrorRecordingFilter(RecentErrors recentErrors) {
        this.recentErrors = recentErrors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            recentErrors.record("server", request.getMethod() + " " + request.getRequestURI(),
                    NestedExceptionUtils.getMostSpecificCause(e).toString());
            throw e;
        }
    }
}
