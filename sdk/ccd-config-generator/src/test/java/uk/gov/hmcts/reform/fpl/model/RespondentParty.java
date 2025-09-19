package uk.gov.hmcts.reform.fpl.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import uk.gov.hmcts.reform.fpl.enums.PartyType;
import uk.gov.hmcts.reform.fpl.model.common.Party;
import uk.gov.hmcts.reform.fpl.model.common.Telephone;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

import static org.apache.commons.lang3.StringUtils.defaultString;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = RespondentParty.RespondentPartyBuilder.class)
public final class RespondentParty extends Party {

    public final Telephone telephoneNumber;
    public final String firstName;
    public final String lastName;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    public final LocalDate dateOfBirth;
    private final String gender;
    private final String genderIdentification;
    private final String placeOfBirth;
    private final String relationshipToChild;
    private final String contactDetailsHidden;
    private final String contactDetailsHiddenReason;
    private final String litigationIssues;
    private final String litigationIssuesDetails;

    @NotBlank(message = "Enter the respondent's full name")
    public String getFirstName() {
        return firstName;
    }

    @NotBlank(message = "Enter the respondent's full name")
    public String getLastName() {
        return lastName;
    }

    @NotBlank(message = "Enter the respondent's relationship to child")
    public String getRelationshipToChild() {
        return relationshipToChild;
    }

    @Builder(toBuilder = true, builderClassName = "RespondentPartyBuilder")
    public RespondentParty(String partyId,
                           PartyType partyType,
                           String firstName,
                           String lastName,
                           LocalDate dateOfBirth,
                           Address address,
                           Telephone telephoneNumber,
                           String gender,
                           String genderIdentification,
                           String placeOfBirth,
                           String relationshipToChild,
                           String contactDetailsHidden,
                           String contactDetailsHiddenReason,
                           String litigationIssues,
                           String litigationIssuesDetails) {
        super(partyId, partyType, address);
        this.firstName = firstName;
        this.lastName = lastName;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
        this.genderIdentification = genderIdentification;
        this.placeOfBirth = placeOfBirth;
        this.relationshipToChild = relationshipToChild;
        this.contactDetailsHidden = contactDetailsHidden;
        this.contactDetailsHiddenReason = contactDetailsHiddenReason;
        this.litigationIssues = litigationIssues;
        this.litigationIssuesDetails = litigationIssuesDetails;
        this.telephoneNumber = telephoneNumber;
    }

    @JsonIgnore
    public String getFullName() {
        return String.format("%s %s", defaultString(firstName), defaultString(lastName)).trim();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class RespondentPartyBuilder {
    }
}
