package com.aquiroz.transactions.adapter.in.web;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns the current trace id so a client can quote it when reporting a problem; the same id is in every log line.
 */
@Component
@RequiredArgsConstructor
public class TraceIdResponseFilter extends OncePerRequestFilter {

    static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        chain.doFilter(request, response);
    }
}
