package uk.gov.hmcts.reform.fpl.model.common;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import lombok.AllArgsConstructor;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.enums.PartyType;
import uk.gov.hmcts.reform.fpl.model.Address;

@Data
@AllArgsConstructor
public class Party {
    @CaseField(label = "Party ID", showCondition = "partyType=\"DO_NOT_SHOW\"")
    public final String partyId;
    @CaseField(label = " ", showCondition = "partyType=\"DO_NOT_SHOW\"")
    public final PartyType partyType;
    @CaseField(label = "Current address")
    public final Address address;
}
