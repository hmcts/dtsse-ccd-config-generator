package uk.gov.hmcts.reform.fpl.model;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.FieldType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProceedingType {

    @CaseField(type = FieldType.FixedRadioList, typeParameter = "ProceedingStatus", label = "Are these previous or ongoing proceedings?")
    private final String proceedingStatus;
    @CaseField(label = "Case number")
    private final String caseNumber;
    @CaseField(label = "Date started")
    private final String started;
    @CaseField(label = "Date ended")
    private final String ended;
    @CaseField(label = "Orders made")
    private final String ordersMade;
    @CaseField(label = "Judge", hint = "For example, District Judge Martin Brown")
    private final String judge;
    @CaseField(label = "Names of children involved", type = FieldType.TextArea)
    private final String children;
    @CaseField(label = "Name of guardian")
    private final String guardian;
    @CaseField(label = "Is the same guardian needed?", type = FieldType.YesOrNo)
    private final String sameGuardianNeeded;
    @CaseField(label = "Give reason", showCondition = "sameGuardianNeeded=\"No\"", type = FieldType.TextArea)
    private final String sameGuardianDetails;

}
