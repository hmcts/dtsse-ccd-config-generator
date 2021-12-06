package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Getter
@RequiredArgsConstructor
@ComplexType(generate = false)
public enum ChangeOrganisationApprovalStatus {
  @JsonProperty("0")
  NOT_CONSIDERED("0"),
  @JsonProperty("1")
  APPROVED("1"),
  @JsonProperty("2")
  REJECTED("2");

  private final String value;
}
