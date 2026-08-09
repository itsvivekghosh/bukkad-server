package com.bhukkad.logging;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.*;

public class CachedBodyHttpServletResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream cachedContent = new ByteArrayOutputStream();
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    public CachedBodyHttpServletResponse(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (this.outputStream == null) {
            this.outputStream = new CachedBodyServletOutputStream(
                    getResponse().getOutputStream(), this.cachedContent);
        }
        return this.outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (this.writer == null) {
            this.writer = new PrintWriter(new OutputStreamWriter(
                    getOutputStream(), getCharacterEncoding()), true);
        }
        return this.writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (this.writer != null) {
            this.writer.flush();
        }
        if (this.outputStream != null) {
            this.outputStream.flush();
        }
    }

    public String getBody() {
        return cachedContent.toString();
    }

    private static class CachedBodyServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream original;
        private final ByteArrayOutputStream copy;

        public CachedBodyServletOutputStream(ServletOutputStream original, ByteArrayOutputStream copy) {
            this.original = original;
            this.copy = copy;
        }

        @Override
        public void write(int b) throws IOException {
            original.write(b);
            copy.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            original.write(b);
            copy.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            original.write(b, off, len);
            copy.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            original.flush();
            copy.flush();
        }

        @Override
        public void close() throws IOException {
            original.close();
            copy.close();
        }

        @Override
        public boolean isReady() {
            return original.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            original.setWriteListener(listener);
        }
    }
}