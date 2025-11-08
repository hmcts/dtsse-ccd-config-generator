package uk.gov.hmcts.divorce.callback;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class CallbackLoggingFilter extends OncePerRequestFilter {

    private static final Path LOG_FILE = Paths.get("build", "logs", "http-traffic.log");
    private static final ReentrantLock FILE_LOCK = new ReentrantLock();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        try {
            Files.createDirectories(LOG_FILE.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize http traffic log directory", e);
        }
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        ContentCachingRequestWrapper cachingRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();

        try {
            filterChain.doFilter(cachingRequest, cachingResponse);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            writeLogEntry(cachingRequest, cachingResponse, durationMs);
            cachingResponse.copyBodyToResponse();
        }
    }

    private void writeLogEntry(
        ContentCachingRequestWrapper request,
        ContentCachingResponseWrapper response,
        long durationMs
    ) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("method", request.getMethod());
        entry.put("uri", request.getRequestURI());
        entry.put("query", request.getQueryString());
        entry.put("status", response.getStatus());
        entry.put("durationMs", durationMs);
        entry.put("requestBody", toPayload(request.getContentAsByteArray(), request.getCharacterEncoding()));
        entry.put("responseBody", toPayload(response.getContentAsByteArray(), response.getCharacterEncoding()));

        byte[] line;
        try {
            line = (OBJECT_MAPPER.writeValueAsString(entry) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to serialise HTTP traffic log entry", e);
            return;
        }

        FILE_LOCK.lock();
        try {
            Files.write(LOG_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write HTTP traffic log entry", e);
        } finally {
            FILE_LOCK.unlock();
        }
    }

    private String toPayload(byte[] body, String encoding) {
        if (body == null || body.length == 0) {
            return "";
        }
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return new String(body, charset);
    }
}
