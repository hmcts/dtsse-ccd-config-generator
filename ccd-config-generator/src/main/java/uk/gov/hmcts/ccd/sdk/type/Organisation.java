package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "Organisation", generate = false)
public class Organisation {
  @JsonProperty("OrganisationID")
  private String organisationId;

  @JsonProperty("OrganisationName")
  private String organisationName;

  @JsonCreator
  public Organisation(
      @JsonProperty("OrganisationId") String organisationId,
      @JsonProperty("OrganisationName") String organisationName
  ) {
    this.organisationId = organisationId;
    this.organisationName = organisationName;
  }
}
