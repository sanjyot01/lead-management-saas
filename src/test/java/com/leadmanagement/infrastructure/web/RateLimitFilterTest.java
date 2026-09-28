package com.leadmanagement.infrastructure.web;

import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.lead.application.RateLimitService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the 429 enforcement shape — the Redis-backed budget
 * behavior itself is proven by RateLimitServiceTest against real Redis.
 */
class RateLimitFilterTest {

    private RateLimitService rateLimitService;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        filter = new RateLimitFilter(rateLimitService, new SimpleMeterRegistry(), 1000);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void allowsRequestWithinBudget() throws Exception {
        TenantContext.setCurrentTenantId(UUID.randomUUID());
        when(rateLimitService.tryConsume(anyInt(), any())).thenReturn(true);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(post("/api/v1/leads"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("request should reach the rest of the chain").isNotNull();
    }

    @Test
    void returns429WithRetryAfterWhenBudgetExhausted() throws Exception {
        TenantContext.setCurrentTenantId(UUID.randomUUID());
        when(rateLimitService.tryConsume(anyInt(), any())).thenReturn(false);
        when(rateLimitService.windowResetSeconds(any())).thenReturn(1800L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(post("/api/v1/leads"), response, chain);

        assertThat(chain.getRequest()).as("blocked request must not reach the chain").isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1800");
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void skipsEnforcementWithoutTenantContext() throws Exception {
        TenantContext.clear();

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(post("/api/v1/leads"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimitService, never()).tryConsume(anyInt(), any());
    }

    @Test
    void onlyLeadIngestionIsMetered() throws Exception {
        TenantContext.setCurrentTenantId(UUID.randomUUID());

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/leads");
        get.setRequestURI("/api/v1/leads");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(get, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimitService, never()).tryConsume(anyInt(), any());

        MockHttpServletRequest authPost = new MockHttpServletRequest("POST", "/api/auth/login");
        authPost.setRequestURI("/api/auth/login");
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(authPost, new MockHttpServletResponse(), chain2);

        assertThat(chain2.getRequest()).isNotNull();
        verify(rateLimitService, never()).tryConsume(anyInt(), any(Duration.class));
    }

    private MockHttpServletRequest post(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
