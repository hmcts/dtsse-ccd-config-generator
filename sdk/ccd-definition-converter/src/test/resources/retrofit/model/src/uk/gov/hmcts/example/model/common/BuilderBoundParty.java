package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * A complex type in the SSCS {@code Appeal} shape: {@code @Data @Builder} plus a hand-written
 * multi-parameter {@code @JsonCreator} constructor written one parameter per line. Lombok binds the
 * builder to that constructor, so synthesising a field on its own would make the generated builder
 * pass an argument the constructor does not declare. The patch WIDENS the constructor instead —
 * verified against Lombok 1.18.38, where an extended {@code @JsonCreator} constructor keeps both
 * {@code builder()} and {@code toBuilder()} setting the added field — preserving the one-per-line
 * parameter shape the team wrote.
 */
@Data
@Builder(toBuilder = true)
public class BuilderBoundParty {

  private String appellantName;

  private String benefitType;

  @JsonCreator
  public BuilderBoundParty(@JsonProperty("appellantName") String appellantName,
                           @JsonProperty("benefitType") String benefitType) {
    this.appellantName = appellantName;
    this.benefitType = benefitType;
  }
}
