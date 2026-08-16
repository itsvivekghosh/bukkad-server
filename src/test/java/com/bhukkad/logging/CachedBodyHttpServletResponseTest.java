package com.bhukkad.logging;

import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedBodyHttpServletResponseTest {

    @Test
    void outputStream_copiesBytesAndDelegates() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachedBodyHttpServletResponse cached = new CachedBodyHttpServletResponse(response);

        var stream = cached.getOutputStream();
        assertSame(stream, cached.getOutputStream());
        stream.write('A');
        stream.write(new byte[]{'B', 'C'});
        stream.write(new byte[]{'X', 'D', 'E', 'Y'}, 1, 2);
        stream.flush();
        stream.close();

        assertEquals("ABCDE", cached.getBody());
        assertTrue(stream.isReady());
    }

    @Test
    void writer_andFlushBuffer_writeBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachedBodyHttpServletResponse cached = new CachedBodyHttpServletResponse(response);

        PrintWriter writer = cached.getWriter();
        assertSame(writer, cached.getWriter());
        writer.write("hello");
        cached.flushBuffer();

        assertEquals("hello", cached.getBody());
    }

    @Test
    void setWriteListener_andIsReady_delegateToOriginal() throws Exception {
        java.util.concurrent.atomic.AtomicReference<WriteListener> captured = new java.util.concurrent.atomic.AtomicReference<>();
        jakarta.servlet.ServletOutputStream original = new jakarta.servlet.ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                captured.set(listener);
            }

            @Override
            public void write(int b) {
            }
        };
        jakarta.servlet.http.HttpServletResponse response = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        org.mockito.Mockito.when(response.getOutputStream()).thenReturn(original);
        org.mockito.Mockito.when(response.getCharacterEncoding()).thenReturn("UTF-8");

        CachedBodyHttpServletResponse cached = new CachedBodyHttpServletResponse(response);
        WriteListener listener = new WriteListener() {
            @Override
            public void onWritePossible() {
            }

            @Override
            public void onError(Throwable t) {
            }
        };
        cached.getOutputStream().setWriteListener(listener);
        assertTrue(cached.getOutputStream().isReady());
        assertSame(listener, captured.get());
    }

    @Test
    void flushBuffer_withoutStreams_isSafe() throws Exception {
        CachedBodyHttpServletResponse cached = new CachedBodyHttpServletResponse(new MockHttpServletResponse());
        cached.flushBuffer();
        assertEquals("", cached.getBody());
    }
}
