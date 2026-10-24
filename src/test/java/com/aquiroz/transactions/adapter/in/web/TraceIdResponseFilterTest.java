package com.aquiroz.transactions.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdResponseFilterTest {

    private final Tracer tracer = mock(Tracer.class);
    private final TraceIdResponseFilter filter = new TraceIdResponseFilter(tracer);

    @Test
    void shouldReturnCurrentTraceIdInResponseHeader() throws Exception {
        var span = mock(Span.class);
        var context = mock(TraceContext.class);
        given(tracer.currentSpan()).willReturn(span);
        given(span.context()).willReturn(context);
        given(context.traceId()).willReturn("03db753778318ffc19859ac367099212");
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(TraceIdResponseFilter.TRACE_ID_HEADER))
                .isEqualTo("03db753778318ffc19859ac367099212");
    }

    @Test
    void shouldContinueWithoutHeaderWhenThereIsNoActiveSpan() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getHeader(TraceIdResponseFilter.TRACE_ID_HEADER)).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }
}
