package uk.gov.hmcts.divorce.divorcecase.model.sow014;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Party {
    @CCD(
        showCondition = "partyId=\"NEVER_SHOW\""
    )
    private String partyId;

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
