package uk.gov.hmcts.divorce.divorcecase.model.sow014;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
public class Solicitor {

    @CCD(
        showCondition = "solicitorId=\"NEVER_SHOW\""
    )
    private String solicitorId;
    @CCD(
        label = "Organisation Id"
    )
    private String organisationId;
    @CCD(
        showCondition = "reference=\"NEVER_SHOW\""
    )
    private String reference;

    @CCD(
        label = "Role"
    )
    private String role;
    @CCD(
        showCondition = "version=\"NEVER_SHOW\""
    )
    private String version;
    @CCD(
        label = "First name"
    )
    private String forename;

    @CCD(
        label = "Last name"
    )
    private String surname;
}
