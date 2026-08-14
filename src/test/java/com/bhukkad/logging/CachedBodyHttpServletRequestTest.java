package com.bhukkad.logging;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.BufferedReader;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedBodyHttpServletRequestTest {

    @Test
    void cachesBodyAndAllowsRepeatedReads() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("hello".getBytes());

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);

        assertEquals("hello", cached.getBody());
        assertEquals("hello", new String(cached.getInputStream().readAllBytes()));
        try (BufferedReader reader = cached.getReader()) {
            assertEquals("hello", reader.readLine());
        }

        ServletInputStream stream = cached.getInputStream();
        assertTrue(stream.isReady());
        assertFalse(stream.isFinished());
        assertEquals('h', stream.read());
        stream.readAllBytes();
        assertTrue(stream.isFinished());
        assertThrows(UnsupportedOperationException.class, () -> stream.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() {
            }

            @Override
            public void onAllDataRead() {
            }

            @Override
            public void onError(Throwable t) {
            }
        }));
    }

    @Test
    void isFinished_returnsTrueWhenAvailableThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("x".getBytes());
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        ServletInputStream stream = cached.getInputStream();
        java.lang.reflect.Field field = stream.getClass().getDeclaredField("cachedBodyInputStream");
        field.setAccessible(true);
        field.set(stream, new java.io.InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public int available() throws IOException {
                throw new IOException("unavailable");
            }
        });
        assertTrue(stream.isFinished());
    }

    @Test
    void emptyBody_isFinishedImmediately() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[0]);
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        assertTrue(cached.getInputStream().isFinished());
        assertEquals("", cached.getBody());
    }
}
