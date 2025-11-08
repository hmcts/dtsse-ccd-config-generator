package uk.gov.hmcts.divorce.callback;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

@Slf4j
@Component
public class CallbackLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getRequestURI() != null && request.getRequestURI().startsWith("/callbacks")) {
            ContentCachingRequestWrapper cachingRequest = new ContentCachingRequestWrapper(request);
            filterChain.doFilter(cachingRequest, response);

            String characterEncoding = request.getCharacterEncoding() != null
                ? request.getCharacterEncoding()
                : "UTF-8";
            String payload = new String(cachingRequest.getContentAsByteArray(), characterEncoding);
            log.info("Callback {} payload: {}", request.getRequestURI(), payload);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
