package uk.gov.hmcts.divorce.jsonlegacy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class BaseJsonLegacyController {

  public static final String MARKER = "json-legacy-about-to-submit";
  public static final String ACAS_DOCUMENT_NOTE = "json-legacy-acas-cdam-document";
  public static final String EVENT_INPUT_DOCUMENT_ID = "11111111-1111-1111-1111-111111111111";
  public static final String CALLBACK_DOCUMENT_ID = "22222222-2222-2222-2222-222222222222";
  public static final String CALLBACK_DOCUMENT_HASH = documentHashToken(CALLBACK_DOCUMENT_ID, "EMPLOYMENT", "case-type-a");
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
    if (ACAS_DOCUMENT_NOTE.equals(data.get("note"))) {
      addAcasVisibleDocument(data, "callback-acas-document", CALLBACK_DOCUMENT_ID, CALLBACK_DOCUMENT_HASH);
    }
    aboutToSubmitSawAuthorisation = authorisation != null && !authorisation.isBlank();
    aboutToSubmitSawServiceAuthorisation = serviceAuthorisation != null && !serviceAuthorisation.isBlank();
    return ResponseEntity.ok(aboutToSubmitResponse(data, List.of()));
  }

  public static Map<String, Object> acasDocumentCollectionItem(String id, String documentId, String hashToken) {
    String documentBaseUrl = "http://localhost/documents/" + documentId;
    Map<String, Object> uploadedDocument = new LinkedHashMap<>(Map.of(
        "document_url", documentBaseUrl,
        "document_binary_url", documentBaseUrl + "/binary",
        "document_filename", id + ".pdf"
    ));
    uploadedDocument.put("document_hash", hashToken);

    return acasDocumentCollectionItem(id, uploadedDocument);
  }

  public static Map<String, Object> acasDocumentCollectionItem(String id, String documentId) {
    String documentBaseUrl = "http://localhost/documents/" + documentId;
    return acasDocumentCollectionItem(id, new LinkedHashMap<>(Map.of(
        "document_url", documentBaseUrl,
        "document_binary_url", documentBaseUrl + "/binary",
        "document_filename", id + ".pdf"
    )));
  }

  private static Map<String, Object> acasDocumentCollectionItem(String id, Map<String, Object> uploadedDocument) {
    return new LinkedHashMap<>(Map.of(
        "id", id,
        "value", new LinkedHashMap<>(Map.of(
            "documentType", "ET1",
            "uploadedDocument", uploadedDocument
        ))
    ));
  }

  private void addAcasVisibleDocument(Map<String, Object> data, String id, String documentId, String hashToken) {
    List<Object> documentCollection = new ArrayList<>();
    Object existing = data.get("documentCollection");
    if (existing instanceof List<?> list) {
      documentCollection.addAll(list);
    }

    documentCollection.add(acasDocumentCollectionItem(id, documentId, hashToken));
    data.put("documentCollection", documentCollection);
  }

  private static String documentHashToken(String documentId, String jurisdiction, String caseType) {
    String cdamSalt = System.getenv().getOrDefault("CASE_DOCUMENT_AM_API_S2S_SECRET", "AABBCCDDEEFFGGHH");
    String tokenSource = cdamSalt + documentId + jurisdiction + caseType;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(tokenSource.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(new BigInteger(1, hash).toString(16));
      while (hex.length() < 32) {
        hex.insert(0, "0");
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
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
