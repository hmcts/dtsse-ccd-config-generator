package uk.gov.hmcts.example.map;

import java.util.HashMap;

/**
 * A map-based case model (IA's archetype): a {@code HashMap} subclass with no domain fields to
 * annotate. Retrofit must report "not applicable — use generate mode" (decision 6).
 */
public class AsylumCase extends HashMap<String, Object> {

  public <T> T read(String key) {
    return (T) get(key);
  }
}
