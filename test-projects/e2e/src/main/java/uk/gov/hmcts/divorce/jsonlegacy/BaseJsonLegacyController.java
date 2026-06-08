package uk.gov.hmcts.divorce.jsonlegacy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

public abstract class BaseJsonLegacyController {

  public static final String MARKER = "json-legacy-about-to-submit";
  public static final String CONFIRMATION_HEADER = "# JSON legacy submitted";
  public static final String CONFIRMATION_BODY = "JSON legacy submitted callback ran";
  public static final String EXTERNAL_CONFIRMATION_HEADER = "# JSON legacy external submitted";
  public static final String EXTERNAL_CONFIRMATION_BODY = "hello from external endpoint";
  public static volatile int aboutToSubmitAttempts;
  public static volatile int submittedAttempts;
  public static volatile int externalSubmittedAttempts;
  public static volatile boolean aboutToSubmitSawAuthorisation;
  public static volatile boolean aboutToSubmitSawServiceAuthorisation;
  public static volatile boolean submittedSawCommittedData;
  public static volatile boolean externalSubmittedSawAuthorisation;
  public static volatile boolean externalSubmittedSawServiceAuthorisation;

  private final NamedParameterJdbcTemplate db;

  protected BaseJsonLegacyController(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  @PostMapping("/about-to-submit")
  public ResponseEntity<Map<String, Object>> aboutToSubmit(
      @RequestHeader("Authorization") String authorisation,
      @RequestHeader("ServiceAuthorization") String serviceAuthorisation,
      @RequestBody Map<String, Object> request
  ) {
    aboutToSubmitAttempts++;
    Map<String, Object> data = new LinkedHashMap<>(caseData(request));
    if ("json-legacy-error".equals(data.get("note"))) {
      return ResponseEntity.ok(aboutToSubmitResponse(data, List.of("JSON legacy validation error")));
    }

    data.put("setInAboutToSubmit", MARKER);
    aboutToSubmitSawAuthorisation = authorisation != null && !authorisation.isBlank();
    aboutToSubmitSawServiceAuthorisation = serviceAuthorisation != null && !serviceAuthorisation.isBlank();
    return ResponseEntity.ok(aboutToSubmitResponse(data, List.of()));
  }

  @PostMapping("/submitted")
  public Map<String, Object> submitted(@RequestBody Map<String, Object> request) {
    int attempt = ++submittedAttempts;
    Map<String, Object> data = caseData(request);
    if ("json-legacy-retry".equals(data.get("note")) && attempt < 3) {
      throw new IllegalStateException("retry submitted callback");
    }

    submittedSawCommittedData = storedDataContainsMarker(caseReference(request));
    return Map.of(
        "confirmation_header", CONFIRMATION_HEADER,
        "confirmation_body", CONFIRMATION_BODY
    );
  }

  @PostMapping("/external-submitted")
  public Map<String, Object> externalSubmitted(
      @RequestHeader("Authorization") String authorisation,
      @RequestHeader("ServiceAuthorization") String serviceAuthorisation
  ) {
    externalSubmittedAttempts++;
    externalSubmittedSawAuthorisation = authorisation != null && !authorisation.isBlank();
    externalSubmittedSawServiceAuthorisation = serviceAuthorisation != null && !serviceAuthorisation.isBlank();
    return Map.of(
        "confirmation_header", EXTERNAL_CONFIRMATION_HEADER,
        "confirmation_body", EXTERNAL_CONFIRMATION_BODY
    );
  }

  private Map<String, Object> aboutToSubmitResponse(Map<String, Object> data, List<String> errors) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("data", data);
    response.put("errors", errors);
    response.put("warnings", List.of());
    return response;
  }

  private boolean storedDataContainsMarker(Long caseReference) {
    String stored = db.queryForObject(
        "select data::text from ccd.case_data where reference = :reference",
        Map.of("reference", caseReference),
        String.class
    );
    return stored != null && stored.contains(MARKER);
  }

  public static void reset() {
    aboutToSubmitAttempts = 0;
    submittedAttempts = 0;
    externalSubmittedAttempts = 0;
    aboutToSubmitSawAuthorisation = false;
    aboutToSubmitSawServiceAuthorisation = false;
    submittedSawCommittedData = false;
    externalSubmittedSawAuthorisation = false;
    externalSubmittedSawServiceAuthorisation = false;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> caseData(Map<String, Object> request) {
    Object data = caseDetails(request).get("data");
    if (data instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }

  private Long caseReference(Map<String, Object> request) {
    Object id = caseDetails(request).get("id");
    if (id instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(id.toString());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> caseDetails(Map<String, Object> request) {
    Object details = request.get("case_details");
    if (details instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }
}
