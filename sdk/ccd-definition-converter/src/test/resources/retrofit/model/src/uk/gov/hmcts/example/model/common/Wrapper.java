package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * A hand-rolled CCD collection-element wrapper in the SSCS {@code Bundle}/{@code ScannedDocument}
 * shape: it declares only {@code value} and uses the single-arg {@code @JsonCreator} +
 * {@code @Builder} idiom, while the definition's {@code ComplexTypes} rows for ID {@code Wrapper}
 * describe the payload class {@link WrapperDetails}. CCD serialises every collection element as
 * {@code {id, value}}, so the member namespace the definition addresses is rooted at the VALUE type —
 * the patch must annotate/synthesise onto {@code WrapperDetails}, leaving this wrapper alone.
 * Targeting the wrapper made every one of those members look definition-only and refused them all as
 * builder-binding breaks (111 members across 22 sscs classes).
 */
@Builder(toBuilder = true)
public class Wrapper {

  private WrapperDetails value;

  @JsonCreator
  public Wrapper(@JsonProperty("value") WrapperDetails value) {
    this.value = value;
  }
}
