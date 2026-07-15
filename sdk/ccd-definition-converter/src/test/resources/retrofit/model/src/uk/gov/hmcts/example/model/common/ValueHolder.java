package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/**
 * A {@code @Value} complex type (Lombok makes every field {@code private final}) with a hand-written
 * {@code @JsonCreator} constructor that assigns only its declared field, as Civil's {@code Bundle}
 * does. A synthesised field would be final too and the constructor would not initialise it —
 * "variable might not have been initialized" — so the retrofit patch must NOT synthesise into it,
 * routing the member to a MANUAL_PLACEMENT gap instead (the @Value/final-field guard).
 */
@Value
public class ValueHolder {

  String held;

  @JsonCreator
  public ValueHolder(@JsonProperty("held") String held) {
    this.held = held;
  }
}
