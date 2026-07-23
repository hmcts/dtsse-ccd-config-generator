package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

class ServicePersistenceExceptionHandlerTest {

  private final ServicePersistenceExceptionHandler handler = new ServicePersistenceExceptionHandler();

  @Test
  void handleHttpMessageNotReadableReturnsGenericBadRequestProblem() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ccd-persistence/cases");
    request.setContentType("application/json");
    request.addHeader(IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString());

    var response = handler.handleHttpMessageNotReadable(
        new HttpMessageNotReadableException("Bad JSON"),
        request
    );

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    ProblemDetail problemDetail = response.getBody();
    assertThat(problemDetail).isNotNull();
    assertThat(problemDetail.getTitle()).isEqualTo("Bad Request");
    assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problemDetail.getDetail()).isEqualTo(ServicePersistenceExceptionHandler.FAILED_TO_READ_REQUEST);
    assertThat(problemDetail.getInstance()).isEqualTo(URI.create("/ccd-persistence/cases"));
  }

  @Test
  void jsonDiagnosticsIncludeNestedPathAndLocation() throws Exception {
    JsonMappingException mappingException = readInvalidPayload();
    HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
        "Bad JSON",
        mappingException
    );

    assertThat(ServicePersistenceExceptionHandler.jsonPath(exception))
        .contains("$.caseDetails.id");
    assertThat(ServicePersistenceExceptionHandler.jsonLocation(exception))
        .hasValueSatisfying(location -> assertThat(location)
            .contains("line=", "column=", "byteOffset=", "charOffset="));
  }

  @Test
  void cachedRequestBodyReturnsSingleLineCachedBody() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ccd-persistence/cases");
    request.setCharacterEncoding(StandardCharsets.UTF_8.name());
    request.setContent("{\"caseDetails\":\n{\"id\":\"not-a-number\"}}".getBytes(StandardCharsets.UTF_8));
    ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request);
    wrapper.getInputStream().readAllBytes();

    ServicePersistenceExceptionHandler.CachedRequestBody cachedRequestBody =
        ServicePersistenceExceptionHandler.cachedRequestBody(wrapper);

    assertThat(cachedRequestBody.value())
        .isEqualTo("{\"caseDetails\":\\n{\"id\":\"not-a-number\"}}");
    assertThat(cachedRequestBody.truncated()).isFalse();
  }

  @Test
  void cachedRequestBodyFlagsTruncatedBody() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ccd-persistence/cases");
    request.setContent("{\"caseDetails\":{}}".getBytes(StandardCharsets.UTF_8));
    ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request);
    wrapper.setAttribute(ServicePersistenceRequestCachingFilter.REQUEST_BODY_TRUNCATED_ATTRIBUTE, true);
    wrapper.getInputStream().readAllBytes();

    ServicePersistenceExceptionHandler.CachedRequestBody cachedRequestBody =
        ServicePersistenceExceptionHandler.cachedRequestBody(wrapper);

    assertThat(cachedRequestBody.truncated()).isTrue();
  }

  private JsonMappingException readInvalidPayload() throws Exception {
    try {
      new ObjectMapper().readValue("{\"caseDetails\":{\"id\":\"not-a-number\"}}", Payload.class);
    } catch (JsonMappingException exception) {
      return exception;
    }
    throw new IllegalStateException("Expected invalid payload to fail JSON mapping");
  }

  static class Payload {
    public CaseDetails caseDetails;
  }

  static class CaseDetails {
    public long id;
  }
}
