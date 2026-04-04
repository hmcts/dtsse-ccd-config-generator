package uk.gov.hmcts.ccd.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackRequestContextFilterTest {

  private final CallbackRequestContextFilter filter = new CallbackRequestContextFilter();

  @AfterEach
  void cleanThreadLocal() {
    CallbackRequestContext.clear();
  }

  @Test
  void ccdPersistenceRequestCachesAuthorizationTokenAndWrapsRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ccd-persistence/cases");
    request.addHeader("Authorization", "Bearer token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> tokenSeenInsideChain = new AtomicReference<>();
    AtomicBoolean wrappedRequestSeen = new AtomicBoolean(false);

    filter.doFilter(request, response, (req, res) -> {
      tokenSeenInsideChain.set(CallbackRequestContext.getAuthorizationToken().orElse(null));
      wrappedRequestSeen.set(req instanceof ContentCachingRequestWrapper);
    });

    assertThat(tokenSeenInsideChain.get()).isEqualTo("Bearer token");
    assertThat(wrappedRequestSeen.get()).isTrue();
    assertThat(CallbackRequestContext.getAuthorizationToken()).isEmpty();
  }

  @Test
  void nonCcdPersistenceRequestDoesNotCacheAuthorizationToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/health");
    request.addHeader("Authorization", "Bearer token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> tokenSeenInsideChain = new AtomicReference<>();
    AtomicBoolean wrappedRequestSeen = new AtomicBoolean(true);

    filter.doFilter(request, response, (req, res) -> {
      tokenSeenInsideChain.set(CallbackRequestContext.getAuthorizationToken().orElse(null));
      wrappedRequestSeen.set(req instanceof ContentCachingRequestWrapper);
    });

    assertThat(tokenSeenInsideChain.get()).isNull();
    assertThat(wrappedRequestSeen.get()).isFalse();
    assertThat(CallbackRequestContext.getAuthorizationToken()).isEmpty();
  }

  @Test
  void authorizationTokenIsClearedWhenFilterChainThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ccd-persistence/cases");
    request.addHeader("Authorization", "Bearer token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
      throw new IllegalStateException("boom");
    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    assertThat(CallbackRequestContext.getAuthorizationToken()).isEmpty();
  }
}
