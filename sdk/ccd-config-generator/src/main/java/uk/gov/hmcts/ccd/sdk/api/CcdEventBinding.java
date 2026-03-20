package uk.gov.hmcts.ccd.sdk.api;

import java.util.Objects;

/**
 * Static binding for a DTO-backed decentralised event.
 * Keeps the event id, literal field prefix, and DTO type declared once.
 */
public record CcdEventBinding<D>(String id, String fieldPrefix, Class<D> dtoClass) {

  public CcdEventBinding {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(fieldPrefix, "fieldPrefix must not be null");
    Objects.requireNonNull(dtoClass, "dtoClass must not be null");
  }

  public static <D> CcdEventBinding<D> of(String id, String fieldPrefix, Class<D> dtoClass) {
    return new CcdEventBinding<>(id, fieldPrefix, dtoClass);
  }
}
