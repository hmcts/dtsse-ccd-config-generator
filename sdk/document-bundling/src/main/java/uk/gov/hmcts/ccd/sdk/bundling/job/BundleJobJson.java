package uk.gov.hmcts.ccd.sdk.bundling.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;

/**
 * The outbox wire format. The mapper is module-owned so the stored shape never varies with a
 * consumer's ObjectMapper customisations; the request version stamps every row so a worker that
 * cannot read an old request fails clearly instead of guessing.
 */
final class BundleJobJson {
  static final int REQUEST_VERSION = 1;

  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
  };
  private static final TypeReference<List<DocumentFailure>> DOCUMENT_FAILURES =
      new TypeReference<>() {
      };
  private static final TypeReference<List<BundleJobTransientFailure>> HISTORY =
      new TypeReference<>() {
      };

  /** A persisted payload this worker version cannot read. */
  static final class Unreadable extends RuntimeException {
    Unreadable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final ObjectMapper mapper = JsonMapper.builder()
      .findAndAddModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .build();

  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialise a value for the bundle job outbox", e);
    }
  }

  <T> T read(String json, Class<T> type, String what) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception e) {
      throw new Unreadable("the stored " + what + " could not be read", e);
    }
  }

  Map<String, String> readParameters(String json) {
    try {
      return mapper.readValue(json, STRING_MAP);
    } catch (Exception e) {
      throw new Unreadable("the stored selector parameters could not be read", e);
    }
  }

  List<DocumentFailure> readDocumentFailures(String json) {
    try {
      return json == null ? List.of() : mapper.readValue(json, DOCUMENT_FAILURES);
    } catch (Exception e) {
      throw new IllegalStateException("Stored document failures could not be read", e);
    }
  }

  List<BundleJobTransientFailure> readHistory(String json) {
    try {
      return json == null ? List.of() : mapper.readValue(json, HISTORY);
    } catch (Exception e) {
      return List.of();
    }
  }
}
