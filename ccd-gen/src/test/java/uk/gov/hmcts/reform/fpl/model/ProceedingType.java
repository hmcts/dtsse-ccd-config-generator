package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProceedingType {

    @CCD(type = FieldType.FixedRadioList, typeParameter = "ProceedingStatus", label = "Are these previous or ongoing proceedings?")
    private final String proceedingStatus;
    @CCD(label = "Case number")
    private final String caseNumber;
    @CCD(label = "Date started")
    private final String started;
    @CCD(label = "Date ended")
    private final String ended;
    @CCD(label = "Orders made")
    private final String ordersMade;
    @CCD(label = "Judge", hint = "For example, District Judge Martin Brown")
    private final String judge;
    @CCD(label = "Names of children involved", type = FieldType.TextArea)
    private final String children;
    @CCD(label = "Name of guardian")
    private final String guardian;
    @CCD(label = "Is the same guardian needed?", type = FieldType.YesOrNo)
    private final String sameGuardianNeeded;
    @CCD(label = "Give reason", showCondition = "sameGuardianNeeded=\"No\"", type = FieldType.TextArea)
    private final String sameGuardianDetails;

}
