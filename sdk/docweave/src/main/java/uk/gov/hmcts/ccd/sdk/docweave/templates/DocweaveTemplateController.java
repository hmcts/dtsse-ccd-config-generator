package uk.gov.hmcts.ccd.sdk.docweave.templates;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import com.fasterxml.jackson.databind.JsonNode;
import feign.FeignException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.impl.IdamService;
import uk.gov.hmcts.reform.authorisation.exceptions.InvalidTokenException;
import uk.gov.hmcts.reform.authorisation.exceptions.ServiceException;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;

@RestController
@RequestMapping("/docweave/templates")
@RequiredArgsConstructor
public class DocweaveTemplateController {

  static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";
  private static final String CACHE_CONTROL = "private, no-store";

  private final DocweaveTemplateRepository templates;
  private final IdamService idam;
  private final AuthTokenValidator s2s;
  private final DocweaveTemplatesAutoConfiguration.Properties properties;

  @GetMapping
  public ResponseEntity<SearchResponse> search(
      @RequestHeader(value = AUTHORIZATION, required = false) String userToken,
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceToken,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "all") String scope
  ) {
    UUID user = authenticate(userToken, serviceToken);
    boolean mine = switch (scope.toLowerCase()) {
      case "all" -> false;
      case "mine" -> true;
      default -> throw invalid("Scope must be all or mine");
    };
    List<DocweaveTemplateRepository.Template> found = templates.search(user, query, mine);
    return ok(new SearchResponse(
        found.stream().map(template -> toResponse(template, user)).toList()
    ));
  }

  @PostMapping
  public ResponseEntity<TemplateResponse> create(
      @RequestHeader(value = AUTHORIZATION, required = false) String userToken,
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceToken,
      @RequestBody SaveRequest request
  ) {
    UUID user = authenticate(userToken, serviceToken);
    DocweaveTemplateRepository.Template template =
        templates.create(user, request.title(), request.content(), request.tags(), request.searchableText());
    return respond(HttpStatus.CREATED, toResponse(template, user));
  }

  @PutMapping("/{id}")
  public ResponseEntity<TemplateResponse> update(
      @RequestHeader(value = AUTHORIZATION, required = false) String userToken,
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceToken,
      @PathVariable UUID id,
      @RequestBody UpdateRequest request
  ) {
    UUID user = authenticate(userToken, serviceToken);
    if (request.expectedRevision() == null || request.expectedRevision() < 1) {
      throw invalid("Expected revision must be positive");
    }
    DocweaveTemplateRepository.Template template = templates.update(
        id,
        user,
        request.expectedRevision(),
        request.title(),
        request.content(),
        request.tags(),
        request.searchableText()
    );
    return ok(toResponse(template, user));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @RequestHeader(value = AUTHORIZATION, required = false) String userToken,
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceToken,
      @PathVariable UUID id,
      @RequestParam Long expectedRevision
  ) {
    UUID user = authenticate(userToken, serviceToken);
    if (expectedRevision < 1) {
      throw invalid("Expected revision must be positive");
    }
    templates.delete(id, user, expectedRevision);
    return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).build();
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> handleError(ResponseStatusException error) {
    return respond(error.getStatusCode(), Map.of("message", error.getReason()));
  }

  @ExceptionHandler({
      HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class,
      MissingServletRequestParameterException.class,
      DataIntegrityViolationException.class
  })
  public ResponseEntity<Map<String, String>> handleBadRequest(Exception error) {
    return respond(HttpStatus.BAD_REQUEST, Map.of("message", "Invalid template request"));
  }

  private TemplateResponse toResponse(DocweaveTemplateRepository.Template template, UUID user) {
    return new TemplateResponse(
        template.id(),
        template.title(),
        template.revision(),
        template.updatedAt(),
        template.content(),
        template.tags(),
        template.ownerId().equals(user)
    );
  }

  private <T> ResponseEntity<T> ok(T body) {
    return respond(HttpStatus.OK, body);
  }

  private <T> ResponseEntity<T> respond(HttpStatusCode status, T body) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(body);
  }

  private ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private UUID authenticate(String userToken, String serviceToken) {
    if (userToken == null || userToken.isBlank() || serviceToken == null || serviceToken.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization credentials are required");
    }

    String service;
    try {
      service = s2s.getServiceName(bearer(serviceToken));
    } catch (InvalidTokenException ex) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid ServiceAuthorization token", ex);
    } catch (ServiceException ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Service authentication is unavailable", ex);
    }

    if (properties.getAllowedServices().stream().noneMatch(service::equalsIgnoreCase)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Calling service is not allowed");
    }

    try {
      return UUID.fromString(idam.retrieveUser(userToken).userDetails().getUid());
    } catch (FeignException ex) {
      HttpStatus status = ex.status() >= 400 && ex.status() < 500
          ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE;
      throw new ResponseStatusException(status,
          status == HttpStatus.UNAUTHORIZED
              ? "Invalid Authorization token" : "User authentication is unavailable",
          ex);
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Authorization token", ex);
    } catch (RuntimeException ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "User authentication is unavailable", ex);
    }
  }

  private String bearer(String token) {
    String value = token.trim();
    if (value.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
      value = value.substring(7).trim();
    }
    return "Bearer " + value;
  }

  public record SaveRequest(String title, JsonNode content, List<String> tags, String searchableText) {
  }

  public record UpdateRequest(
      String title,
      JsonNode content,
      Long expectedRevision,
      List<String> tags,
      String searchableText
  ) {
  }

  public record TemplateResponse(
      UUID id,
      String title,
      long revision,
      Instant updatedAt,
      JsonNode content,
      List<String> tags,
      boolean ownedByCurrentUser
  ) {
  }

  public record SearchResponse(List<TemplateResponse> items) {
  }
}
