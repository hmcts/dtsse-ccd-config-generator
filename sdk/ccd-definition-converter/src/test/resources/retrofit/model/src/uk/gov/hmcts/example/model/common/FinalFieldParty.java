package uk.gov.hmcts.example.model.common;

import lombok.Builder;
import lombok.Data;

/**
 * A complex type in the fpl {@code RespondentParty}/{@code ChildParty} shape: {@code @Data} with
 * {@code private final} fields and a hand-written constructor-level {@code @Builder} (NOT a
 * class-level {@code @Builder} bound to the constructor). A definition-only member synthesised here
 * is emitted <em>non-final</em>, which compiles fine and is set through the Lombok setter — verified
 * against Lombok — so it MUST be synthesised, not routed to a gap. The over-broad "any final field"
 * guard used to reject this shape, dropping every definition-only member of these classes (fpl's
 * missing RespondentParty/ChildParty/Solicitor members).
 */
@Data
public final class FinalFieldParty {

  private final String existingFinal;

  @Builder(toBuilder = true)
  public FinalFieldParty(String existingFinal) {
    this.existingFinal = existingFinal;
  }
}
