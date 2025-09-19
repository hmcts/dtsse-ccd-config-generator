package uk.gov.hmcts.ccd.sdk.type;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkScanEnvelope {

  @CCD(
          label = "Envelope id"
  )
  private String id;


  @CCD(
          label = "Action"
  )
  private String action;

}
