package dev.jobqueue.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests that declare a body larger than the limit before the JSON is read into memory.
 * Only the declared Content-Length is checked (a chunked body has none); the payload-size check in
 * {@code JobSubmissionService} still bounds what is stored.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public RequestSizeLimitFilter(@Value("${jobqueue.api.max-body-bytes:262144}") long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getOutputStream().write(("{\"status\":413,\"title\":\"Content Too Large\","
                    + "\"detail\":\"Request body exceeds " + maxBodyBytes + " bytes\"}")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
