package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/**
 * A {@code @Value} complex type (Lombok makes every field {@code private final}) with a hand-written
 * single-line {@code @JsonCreator} constructor that assigns only its declared field, as Civil's
 * {@code Bundle} does. A synthesised field is final too, so the constructor MUST be widened to
 * initialise it — the patch appends the parameter and its assignment rather than refusing the member,
 * and the single-line parameter list stays on one line.
 */
@Value
public class ValueHolder {

  String held;

  @JsonCreator
  public ValueHolder(@JsonProperty("held") String held) {
    this.held = held;
  }
}
