package uk.gov.hmcts.ccd.sdk.impl;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
    name = "ccd.persistence.request-read-failure-observability.enabled",
    havingValue = "true"
)
class ServicePersistenceRequestCachingFilter extends OncePerRequestFilter {

  static final String REQUEST_BODY_TRUNCATED_ATTRIBUTE =
      ServicePersistenceRequestCachingFilter.class.getName() + ".requestBodyTruncated";

  private final int maxRequestBodyBytes;

  ServicePersistenceRequestCachingFilter(
      @Value("${ccd.persistence.request-read-failure-observability.max-request-body-bytes:65536}")
      int maxRequestBodyBytes
  ) {
    this.maxRequestBodyBytes = Math.max(0, maxRequestBodyBytes);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !"/ccd-persistence/cases".equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    if (request instanceof ContentCachingRequestWrapper) {
      filterChain.doFilter(request, response);
      return;
    }

    filterChain.doFilter(
        new RequestBodyCachingWrapper(request, maxRequestBodyBytes),
        response
    );
  }

  private static class RequestBodyCachingWrapper extends ContentCachingRequestWrapper {

    RequestBodyCachingWrapper(HttpServletRequest request, int contentCacheLimit) {
      super(request, contentCacheLimit);
    }

    @Override
    protected void handleContentOverflow(int contentCacheLimit) {
      setAttribute(REQUEST_BODY_TRUNCATED_ATTRIBUTE, true);
    }
  }
}
