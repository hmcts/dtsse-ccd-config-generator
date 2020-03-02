package uk.gov.hmcts.reform.fpl.model.common;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import lombok.AllArgsConstructor;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.enums.PartyType;
import uk.gov.hmcts.reform.fpl.model.Address;

@Data
@AllArgsConstructor
public class Party {
    @CCD(label = "Party ID", showCondition = "partyType=\"DO_NOT_SHOW\"")
    public final String partyId;
    @CCD(label = " ", showCondition = "partyType=\"DO_NOT_SHOW\"")
    public final PartyType partyType;
    @CCD(label = "Current address")
    public final Address address;
}
