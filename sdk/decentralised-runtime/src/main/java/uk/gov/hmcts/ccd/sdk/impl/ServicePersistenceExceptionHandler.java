package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.ContentCachingRequestWrapper;

@Slf4j
@ConditionalOnProperty(
    name = "ccd.persistence.request-read-failure-observability.enabled",
    havingValue = "true"
)
@RestControllerAdvice(assignableTypes = ServicePersistenceController.class)
class ServicePersistenceExceptionHandler {

  static final String FAILED_TO_READ_REQUEST = "Failed to read request";

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(
      HttpMessageNotReadableException exception,
      HttpServletRequest request
  ) {
    CachedRequestBody cachedRequestBody = cachedRequestBody(request);
    log.warn(
        "Failed to read CCD persistence request body: method={} uri={} query={} contentType={} "
            + "contentLength={} idempotencyKey={} jsonPath={} jsonLocation={} requestBodyTruncated={} "
            + "requestBody={} cause={}",
        request.getMethod(),
        request.getRequestURI(),
        request.getQueryString(),
        request.getContentType(),
        request.getContentLengthLong(),
        request.getHeader(IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER),
        jsonPath(exception).orElse(""),
        jsonLocation(exception).orElse(""),
        cachedRequestBody.truncated(),
        cachedRequestBody.value(),
        (exception.getMostSpecificCause() != null
            ? exception.getMostSpecificCause().getMessage()
            : exception.getMessage()),
        exception
    );

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        FAILED_TO_READ_REQUEST
    );
    problemDetail.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
    problemDetail.setInstance(URI.create(request.getRequestURI()));

    return ResponseEntity.badRequest().body(problemDetail);
  }

  static CachedRequestBody cachedRequestBody(HttpServletRequest request) {
    if (!(request instanceof ContentCachingRequestWrapper wrapper)) {
      return new CachedRequestBody("", false);
    }

    byte[] body = wrapper.getContentAsByteArray();
    if (body.length == 0) {
      return new CachedRequestBody("", false);
    }

    return new CachedRequestBody(
        singleLinePayload(new String(body, charset(request.getCharacterEncoding()))),
        Boolean.TRUE.equals(request.getAttribute(
            ServicePersistenceRequestCachingFilter.REQUEST_BODY_TRUNCATED_ATTRIBUTE
        ))
    );
  }

  static Optional<String> jsonPath(Throwable exception) {
    return findCause(exception, JsonMappingException.class)
        .filter(mappingException -> !mappingException.getPath().isEmpty())
        .map(ServicePersistenceExceptionHandler::formatJsonPath);
  }

  static Optional<String> jsonLocation(Throwable exception) {
    return findCause(exception, JsonProcessingException.class)
        .flatMap(e -> Optional.ofNullable(e.getLocation()))
        .map(ServicePersistenceExceptionHandler::formatJsonLocation);
  }

  private static String formatJsonPath(JsonMappingException exception) {
    StringBuilder path = new StringBuilder("$");
    for (JsonMappingException.Reference reference : exception.getPath()) {
      if (reference.getFieldName() != null) {
        path.append(".").append(reference.getFieldName());
      } else if (reference.getIndex() >= 0) {
        path.append("[").append(reference.getIndex()).append("]");
      }
    }
    return path.toString();
  }

  private static String formatJsonLocation(JsonLocation location) {
    return "line=" + location.getLineNr()
        + ",column=" + location.getColumnNr()
        + ",byteOffset=" + location.getByteOffset()
        + ",charOffset=" + location.getCharOffset();
  }

  private static String singleLinePayload(String payload) {
    return payload
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
  }

  private static Charset charset(String encoding) {
    if (encoding == null || encoding.isBlank()) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(encoding);
    } catch (UnsupportedCharsetException exception) {
      return StandardCharsets.UTF_8;
    }
  }

  private static <T extends Throwable> Optional<T> findCause(Throwable exception, Class<T> type) {
    Throwable current = exception;
    while (current != null) {
      if (type.isInstance(current)) {
        return Optional.of(type.cast(current));
      }
      current = current.getCause();
    }
    return Optional.empty();
  }

  record CachedRequestBody(String value, boolean truncated) {
  }
}
