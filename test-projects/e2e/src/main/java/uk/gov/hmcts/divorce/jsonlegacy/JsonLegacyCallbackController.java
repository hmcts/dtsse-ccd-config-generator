package uk.gov.hmcts.divorce.jsonlegacy;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

@RestController
@RequestMapping("/jsonLegacy")
public class JsonLegacyCallbackController {

  public static final String MARKER = "json-legacy-about-to-submit";
  public static final String CONFIRMATION_HEADER = "# JSON legacy submitted";
  public static final String CONFIRMATION_BODY = "JSON legacy submitted callback ran";
  public static final AtomicInteger aboutToSubmitAttempts = new AtomicInteger();
  public static final AtomicInteger submittedAttempts = new AtomicInteger();
  public static final AtomicBoolean aboutToSubmitSawAuthorisation = new AtomicBoolean();
  public static final AtomicBoolean submittedSawCommittedData = new AtomicBoolean();

  private final NamedParameterJdbcTemplate db;

  public JsonLegacyCallbackController(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  public static void reset() {
    aboutToSubmitAttempts.set(0);
    submittedAttempts.set(0);
    aboutToSubmitSawAuthorisation.set(false);
    submittedSawCommittedData.set(false);
  }

  @PostMapping("/about-to-submit")
  public ResponseEntity<AboutToSubmitResponse> aboutToSubmit(
      @RequestHeader("Authorization") String authorisation,
      @RequestBody JsonCallbackRequest request
  ) {
    aboutToSubmitAttempts.incrementAndGet();
    Map<String, Object> data = new LinkedHashMap<>(request.caseDetails().getData());
    if ("json-legacy-error".equals(data.get("note"))) {
      return ResponseEntity.ok(new AboutToSubmitResponse(data, List.of("JSON legacy validation error"), List.of()));
    }

    data.put("setInAboutToSubmit", MARKER);
    aboutToSubmitSawAuthorisation.set(authorisation != null && !authorisation.isBlank());
    return ResponseEntity.ok(new AboutToSubmitResponse(data, List.of(), List.of()));
  }

  @PostMapping("/submitted")
  public SubmittedResponse submitted(@RequestBody JsonCallbackRequest request) {
    int attempt = submittedAttempts.incrementAndGet();
    Map<String, Object> data = request.caseDetails().getData();
    if ("json-legacy-retry".equals(data.get("note")) && attempt < 3) {
      throw new IllegalStateException("retry submitted callback");
    }

    submittedSawCommittedData.set(storedDataContainsMarker(request.caseDetails().getId()));
    return new SubmittedResponse(CONFIRMATION_HEADER, CONFIRMATION_BODY);
  }

  private boolean storedDataContainsMarker(Long caseReference) {
    String stored = db.queryForObject(
        "select data::text from ccd.case_data where reference = :reference",
        Map.of("reference", caseReference),
        String.class
    );
    return stored != null && stored.contains(MARKER);
  }

  public record JsonCallbackRequest(@JsonProperty("case_details") CaseDetails caseDetails) {
  }

  public record AboutToSubmitResponse(
      Map<String, Object> data,
      List<String> errors,
      List<String> warnings
  ) {
    public AboutToSubmitResponse {
      errors = errors == null ? List.of() : List.copyOf(errors);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
      data = data == null ? Map.of() : new LinkedHashMap<>(data);
    }
  }

  public record SubmittedResponse(
      @JsonProperty("confirmation_header") String confirmationHeader,
      @JsonProperty("confirmation_body") String confirmationBody
  ) {
  }
}
