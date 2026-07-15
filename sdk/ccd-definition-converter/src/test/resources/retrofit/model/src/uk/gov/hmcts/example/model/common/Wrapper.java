package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * A complex type using the hand-written single-arg {@code @JsonCreator} + {@code @Builder} idiom, as
 * SSCS's {@code Bundle}/{@code ScannedDocument} do. Lombok binds the builder to the explicit
 * constructor, so appending a synthesised field breaks the builder's constructor binding — the
 * retrofit patch must NOT synthesise into such a class (bug B3), routing the member to the gap
 * report for manual placement instead.
 */
@Builder(toBuilder = true)
public class Wrapper {

  private WrapperDetails value;

  @JsonCreator
  public Wrapper(@JsonProperty("value") WrapperDetails value) {
    this.value = value;
  }
}
