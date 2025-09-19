package uk.gov.hmcts.ccd.sdk.type;

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

  private String id;
  private PreviousOrganisation value;
}
