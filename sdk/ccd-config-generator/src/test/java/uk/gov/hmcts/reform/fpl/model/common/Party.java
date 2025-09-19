package uk.gov.hmcts.reform.fpl.model.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.reform.fpl.enums.PartyType;
import uk.gov.hmcts.reform.fpl.model.Address;

@Data
@AllArgsConstructor
@NoArgsConstructor(force = true)
public class Party {
    // REFACTOR: 03/12/2019 This needs to be private, effects tests as well
    public final String partyId;
    public final PartyType partyType;
    public final Address address;

}
