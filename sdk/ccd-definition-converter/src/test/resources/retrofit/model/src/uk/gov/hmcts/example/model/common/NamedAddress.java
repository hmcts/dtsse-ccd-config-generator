package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * Civil's {@code Address} shape: a class-level {@code @JsonNaming(UpperCamelCaseStrategy)}, so
 * {@code addressLine1} serialises (and appears in the definition's {@code ListElementCode}) as
 * {@code AddressLine1} while the naming-strategy-BLIND SDK would generate {@code addressLine1}. The
 * {@code CaseEventToComplexTypes} walk closes that gap by resolving through the strategy and recording
 * the reliance, which the patch pins with an explicit {@code @JsonProperty} (see
 * {@code RetrofitPinnedNames}).
 *
 * <p>Deliberately NOT a definition complex type and NOT referenced from {@code CaseData}: Civil's real
 * {@code Address} is declared as the SDK-predefined {@code AddressUK}, so the definition never lists its
 * members and the complex-type member pass never visits it — the pin needs its own pass. It also carries
 * NO {@code @JsonProperty} import, so the patch must add one.
 */
@Data
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class NamedAddress {

  private String addressLine1;

  private String postTown;

  private String county;
}
