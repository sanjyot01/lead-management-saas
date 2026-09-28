package com.leadmanagement.infrastructure.idempotency;

import com.leadmanagement.infrastructure.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * Unit test for the filter's orchestration — the claim/replay/expiry
 * database semantics are proven by IdempotencyServiceDockerComposeTest
 * against real Postgres.
 */
class IdempotencyFilterTest {

    private IdempotencyService idempotencyService;
    private IdempotencyFilter filter;

    @BeforeEach
    void setUp() {
        idempotencyService = mock(IdempotencyService.class);
        filter = new IdempotencyFilter(idempotencyService, new SimpleMeterRegistry());
        TenantContext.setCurrentTenantId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void requestWithoutHeaderPassesThroughUntouched() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(leadPost(null), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void newClaimProcessesAndCachesTheResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setStatus(201);
                resp.getWriter().write("{\"id\":\"lead-123\"}");
            }
        });

        filter.doFilter(leadPost("key-1"), response, chain);

        verify(idempotencyService).claim(eq("key-1"), anyString());
        verify(idempotencyService).complete(eq("key-1"), eq(201), eq("{\"id\":\"lead-123\"}"));
        // copyBodyToResponse must deliver the captured body to the real response
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"lead-123\"}");
    }

    @Test
    void duplicateKeyReplaysCachedResponseWithoutTouchingTheChain() throws Exception {
        doThrow(new DataIntegrityViolationException("duplicate"))
            .when(idempotencyService).claim(eq("key-1"), anyString());
        when(idempotencyService.resolveExisting(eq("key-1"), anyString()))
            .thenReturn(new IdempotencyClaimResult.Replay(201, "{\"id\":\"cached\"}"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(leadPost("key-1"), response, chain);

        assertThat(chain.getRequest()).as("replay must not re-execute the request").isNull();
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"cached\"}");
        assertThat(response.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    }

    @Test
    void inFlightDuplicateGets409() throws Exception {
        doThrow(new DataIntegrityViolationException("duplicate"))
            .when(idempotencyService).claim(eq("key-1"), anyString());
        when(idempotencyService.resolveExisting(eq("key-1"), anyString()))
            .thenReturn(new IdempotencyClaimResult.InFlight());

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(leadPost("key-1"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("already being processed");
    }

    @Test
    void reusedKeyWithDifferentBodyGets422() throws Exception {
        doThrow(new DataIntegrityViolationException("duplicate"))
            .when(idempotencyService).claim(eq("key-1"), anyString());
        when(idempotencyService.resolveExisting(eq("key-1"), anyString()))
            .thenReturn(new IdempotencyClaimResult.HashMismatch());

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(leadPost("key-1"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("different request body");
    }

    @Test
    void nonReplayableResponseReleasesTheClaimInsteadOfCachingIt() throws Exception {
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                resp.setStatus(500);
            }
        });

        filter.doFilter(leadPost("key-1"), new MockHttpServletResponse(), chain);

        verify(idempotencyService).release("key-1");
        verify(idempotencyService, never()).complete(anyString(), eq(500), anyString());
    }

    @Test
    void unauthenticatedRequestSkipsIdempotency() throws Exception {
        TenantContext.clear();

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(leadPost("key-1"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(idempotencyService);
    }

    private MockHttpServletRequest leadPost(String idempotencyKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/leads");
        request.setRequestURI("/api/v1/leads");
        request.setContentType("application/json");
        request.setContent("{\"email\":\"test@example.com\"}".getBytes());
        if (idempotencyKey != null) {
            request.addHeader(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return request;
    }
}
