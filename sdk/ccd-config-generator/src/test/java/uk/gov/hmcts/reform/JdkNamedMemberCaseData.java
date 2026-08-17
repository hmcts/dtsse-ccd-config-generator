package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link JdkNamedMemberCaseType}.
 */
@Data
public class JdkNamedMemberCaseData {

  @CCD(label = "Orders to send", access = {SolicitorAccess.class})
  private JdkNamedMemberWrapper ordersToSend;
}
