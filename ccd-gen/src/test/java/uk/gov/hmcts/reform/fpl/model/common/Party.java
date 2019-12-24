package uk.gov.hmcts.reform.fpl.model.common;

import ccd.sdk.types.CaseField;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.checkerframework.checker.units.qual.C;
import uk.gov.hmcts.reform.fpl.enums.PartyType;
import uk.gov.hmcts.reform.fpl.model.Address;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class Party {
    @CaseField(label = "Party ID", showCondition = "partyType=\"DO_NOT_SHOW\"")
    public final String partyId;
    @CaseField(label = " ", showCondition = "partyType=\"DO_NOT_SHOW\"")
    public final PartyType partyType;
    @CaseField(label = "First name")
    public final String firstName;
    @CaseField(label = "Last name")
    public final String lastName;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @CaseField(label = "Date of birth", hint = "For example, 31 3 1980")
    public final LocalDate dateOfBirth;
    @CaseField(label = "Current address")
    public final Address address;
    @CaseField(label = "Telephone")
    public final Telephone telephoneNumber;
}
