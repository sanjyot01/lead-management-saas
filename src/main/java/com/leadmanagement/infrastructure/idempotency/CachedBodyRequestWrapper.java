package com.leadmanagement.infrastructure.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the request body once, up front, so IdempotencyFilter can hash it
 * BEFORE the controller consumes the stream — a servlet input stream can
 * only be read once, and the hash must exist before processing starts (the
 * whole point of reserve-first is claiming before acting).
 *
 * Bodies here are small JSON payloads (CreateLeadRequest); buffering them in
 * memory is fine. Anything huge would be rejected by validation anyway.
 */
class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    byte[] getBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return buffer.read();
            }

            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async reads not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
