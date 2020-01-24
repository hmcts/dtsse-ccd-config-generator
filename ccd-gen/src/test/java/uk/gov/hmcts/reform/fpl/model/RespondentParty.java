package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import uk.gov.hmcts.ccd.sdk.types.ComplexType;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import uk.gov.hmcts.ccd.sdk.types.Label;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import uk.gov.hmcts.reform.fpl.enums.PartyType;
import uk.gov.hmcts.reform.fpl.model.common.IdentifiedParty;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = RespondentParty.RespondentPartyBuilder.class)
@ComplexType(border = "---", borderId = "respondent_border")
public final class RespondentParty extends IdentifiedParty {
    @CaseField(type = FieldType.FixedList, typeParameter = "GenderList", label = "Gender")
    private final String gender;
    @CaseField(label = "What gender do they identify with?", showCondition = "gender=\"They identify in another way\"")
    private final String genderIdentification;
    @CaseField(label = "Place of birth", hint = "For example, town or city")
    private final String placeOfBirth;
    @Label(id = "relationshipLabel", value = "## Relationship to the child")
    @CaseField(type = FieldType.TextArea, label = "What is the respondent’s relationship to the child or children in this case?", hint = "Include: the name of the child or children, the respondent’s relationship to them and any details if you’re not sure this person has parental responsibility")
    private final String relationshipToChild;

    @CaseField(type = FieldType.YesOrNo, label = "Do you need contact details hidden from other parties?")
    private final String contactDetailsHidden;
    @CaseField(type= FieldType.TextArea, label = "Give reason", showCondition = "contactDetailsHidden=\"Yes\"")
    private final String contactDetailsHiddenReason;

    @Label(id = "proceedingsLabel", value = "## Ability to take part in proceedings")
    @CaseField(type = FieldType.FixedRadioList, typeParameter = "LitigationCapacityIssues", label = "Do you believe this person will have problems with litigation capacity (understanding what's happening in the case)?")
    private final String litigationIssues;

    @CaseField(type=FieldType.TextArea, showCondition = "litigationIssues=\"YES\"", label = "Give details, including assessment outcomes and referrals to health services")
    private final String litigationIssuesDetails;

    @NotBlank(message = "Enter the respondent's full name")
    public String getFirstName() {
        return super.getFirstName();
    }

    @NotBlank(message = "Enter the respondent's full name")
    public String getLastName() {
        return super.getLastName();
    }

    @Builder(toBuilder = true, builderClassName = "RespondentPartyBuilder")
    public RespondentParty(String partyId,
                           PartyType partyType,
                           String firstName,
                           String lastName,
                           LocalDate dateOfBirth,
                           Address address,
                           String gender,
                           String genderIdentification,
                           String placeOfBirth,
                           String relationshipToChild,
                           String contactDetailsHidden,
                           String contactDetailsHiddenReason,
                           String litigationIssues,
                           String litigationIssuesDetails) {
        super(partyId, partyType, address, firstName, lastName, dateOfBirth);
        this.gender = gender;
        this.genderIdentification = genderIdentification;
        this.placeOfBirth = placeOfBirth;
        this.relationshipToChild = relationshipToChild;
        this.contactDetailsHidden = contactDetailsHidden;
        this.contactDetailsHiddenReason = contactDetailsHiddenReason;
        this.litigationIssues = litigationIssues;
        this.litigationIssuesDetails = litigationIssuesDetails;
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class RespondentPartyBuilder {
    }
}
