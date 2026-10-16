package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * A {@code @Builder} complex type with TWO hand-written constructors, neither delegating to the other,
 * whose signatures differ by one {@code String} — the type a synthesised member would add. Widening
 * both makes the shorter one's widened form {@code (String, String)} occupy exactly the signature the
 * longer one's narrow delegating overload needs, so this class cannot be patched at all: keeping both
 * overloads declares the same constructor twice, and dropping one silently rebinds existing
 * {@code new TwoConstructorParty(a, b)} calls to the widened 1-arg constructor. The member is refused
 * to a manual-placement gap.
 */
@Data
@Builder(toBuilder = true)
public class TwoConstructorParty {

  private String primary;
  private String secondary;

  public TwoConstructorParty(String primary) {
    this.primary = primary;
  }

  @JsonCreator
  public TwoConstructorParty(@JsonProperty("primary") String primary,
                             @JsonProperty("secondary") String secondary) {
    this.primary = primary;
    this.secondary = secondary;
  }
}
