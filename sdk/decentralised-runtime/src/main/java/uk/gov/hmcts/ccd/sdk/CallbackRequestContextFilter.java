package uk.gov.hmcts.ccd.sdk;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@Component
class CallbackRequestContextFilter extends OncePerRequestFilter {

  private static final String CCD_PERSISTENCE_PATH = "/ccd-persistence";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(CCD_PERSISTENCE_PATH);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
    ContentCachingRequestWrapper cachingRequest = request instanceof ContentCachingRequestWrapper wrapped
        ? wrapped
        : new ContentCachingRequestWrapper(request);

    CallbackRequestContext.cacheAuthorizationToken(cachingRequest.getHeader(AUTHORIZATION));
    try {
      filterChain.doFilter(cachingRequest, response);
    } finally {
      CallbackRequestContext.clear();
    }
  }
}
