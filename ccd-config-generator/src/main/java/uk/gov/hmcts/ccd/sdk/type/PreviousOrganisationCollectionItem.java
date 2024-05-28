package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@ComplexType
public class PreviousOrganisationCollectionItem {

  @JsonProperty("id")
  private String id;

  @JsonProperty("value")
  private PreviousOrganisation value;
}
