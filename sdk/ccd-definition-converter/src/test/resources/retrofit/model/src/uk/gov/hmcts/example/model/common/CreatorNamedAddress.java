package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * fpl's {@code Address} shape: an immutable value class whose {@code @JsonProperty} lives on the
 * {@code @JsonCreator} CONSTRUCTOR PARAMETERS rather than on the fields. Jackson honours it in both
 * directions, so {@code addressLine1} really does appear in the definition's {@code ListElementCode} as
 * {@code AddressLine1} — but the SDK's {@code PropertyUtils.getPropertyName} reads {@code @JsonProperty}
 * only off the field and the read method, so it would generate {@code addressLine1}. The
 * {@code CaseEventToComplexTypes} walk closes that gap the same way it does for a class-level
 * {@code @JsonNaming}: resolve through the parameter's id and record the reliance, which the patch pins
 * with an explicit FIELD-level {@code @JsonProperty} (see {@code RetrofitPinnedNames}).
 *
 * <p>Distinct from {@link NamedAddress} in exactly the respect that matters: there is no class-level
 * strategy here, so a patch that re-derived the id from {@code @JsonNaming} rather than taking the one
 * the walk matched would pin NOTHING at all — which is the trap, not merely a missed row. Like
 * {@code NamedAddress} it is deliberately not a definition complex type and not referenced from
 * {@code CaseData}, so only the pin pass visits it; unlike it, the {@code @JsonProperty} import is
 * already present (the pin must not add a duplicate).
 */
@Data
public class CreatorNamedAddress {

  private final String addressLine1;

  private final String postTown;

  @JsonCreator
  public CreatorNamedAddress(@JsonProperty("AddressLine1") String addressLine1,
      @JsonProperty("PostTown") String postTown) {
    this.addressLine1 = addressLine1;
    this.postTown = postTown;
  }
}
