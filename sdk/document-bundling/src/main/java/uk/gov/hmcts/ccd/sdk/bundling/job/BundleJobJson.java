package uk.gov.hmcts.ccd.sdk.bundling.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The queue's JSON format. The mapper is module-owned so the stored shape never varies with a
 * consumer's ObjectMapper customisations.
 */
final class BundleJobJson {

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
      throw new IllegalStateException("Failed to serialise a value for the bundle job queue", e);
    }
  }

  <T> T read(String json, Class<T> type, String what) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception e) {
      throw new Unreadable("the stored " + what + " could not be read", e);
    }
  }

  <T> T read(String json, TypeReference<T> type, String what) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception e) {
      throw new Unreadable("the stored " + what + " could not be read", e);
    }
  }
}
