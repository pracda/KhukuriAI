package ai.khukuri.ingest.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Transparently decompresses gzip request bodies.
 *
 * <p>OTLP senders compress by default — the OpenTelemetry Collector and the language SDKs
 * all set {@code Content-Encoding: gzip} out of the box — and the servlet container does
 * not decompress request bodies for you. Without this, a real OTLP client's telemetry is
 * accepted at the edge and then dies in the parser on the gzip magic byte, which looks
 * like corrupt data rather than a missing feature.
 */
@Component
public class GzipRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            chain.doFilter(new GzipRequestWrapper(request), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private static final class GzipRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] decompressed;

        GzipRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            try (InputStream gzip = new GZIPInputStream(request.getInputStream())) {
                this.decompressed = gzip.readAllBytes();
            }
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(decompressed);
        }

        @Override
        public int getContentLength() {
            return decompressed.length;
        }

        @Override
        public long getContentLengthLong() {
            return decompressed.length;
        }

        /**
         * The body is no longer encoded, and it is no longer the advertised length.
         * Leaving the original Content-Length in place truncates the body at the
         * compressed size, which silently delivers half a JSON document downstream.
         */
        @Override
        public String getHeader(String name) {
            if (HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) {
                return null;
            }
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                return String.valueOf(decompressed.length);
            }
            return super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            if (HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) {
                return java.util.Collections.emptyEnumeration();
            }
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                return java.util.Collections.enumeration(
                        java.util.List.of(String.valueOf(decompressed.length)));
            }
            return super.getHeaders(name);
        }

        @Override
        public int getIntHeader(String name) {
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                return decompressed.length;
            }
            return super.getIntHeader(name);
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {

        private final byte[] data;
        private int position;

        ByteArrayServletInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public int available() {
            return data.length - position;
        }

        @Override
        public boolean isFinished() {
            return position >= data.length;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Async reads are not used on this path");
        }
    }
}
