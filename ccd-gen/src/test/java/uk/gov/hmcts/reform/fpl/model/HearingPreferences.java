package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import uk.gov.hmcts.ccd.sdk.types.ComplexType;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@ComplexType(label = "Is any of the following needed to help someone take part in a hearing?", labelId = "hearingPreferencesLabel")
public class HearingPreferences {
    @CCD(type = FieldType.YesOrNo, label = "Spoken or written Welsh")
    private final String welsh;
    @CCD(type = FieldType.YesOrNo, label = "Interpreter", hint = "Including sign language")
    private final String interpreter;
    @CCD(type = FieldType.YesOrNo, label = "Intermediary", hint = "Including sign language")
    private final String intermediary;
    @CCD(type = FieldType.TextArea, label = "Give details", showCondition = "welsh=\"Yes\"")
    private final String welshDetails;
    @CCD(type = FieldType.TextArea, label = "Give details including person, language and dialect", showCondition = "interpreter=\"Yes\"")
    private final String interpreterDetails;
    @CCD(type = FieldType.YesOrNo, label = "Facilities or assistance for a disability")
    private final String disabilityAssistance;
    @CCD(type = FieldType.TextArea, label = "Give details", showCondition = "intermediary=\"Yes\"")
    private final String intermediaryDetails;
    @CCD(type = FieldType.YesOrNo, label = "Separate waiting room or other security measures", hint = "For example, mother and father need to be in separate waiting rooms as history of domestic violence")
    private final String extraSecurityMeasures;
    @CCD(type = FieldType.TextArea, label = "Give details", showCondition = "extraSecurityMeasures=\"Yes\"")
    private final String extraSecurityMeasuresDetails;
    @CCD(type = FieldType.TextArea, label = "Give details", showCondition = "disabilityAssistance=\"Yes\"")
    private final String disabilityAssistanceDetails;
    @CCD(type = FieldType.YesOrNo, label = "Something else", hint = "For example, mother and father need to be in separate waiting rooms as history of domestic violence")
    private final String somethingElse;
    @CCD(type = FieldType.TextArea, label = "Give details", showCondition = "somethingElse=\"Yes\"")
    private final String somethingElseDetails;

    @CCD(type = FieldType.YesOrNo, label = "Litigation capacity issues", showCondition = "litigation=\"DO_NOT_SHOW\"")
    private final String litigation;
    @CCD(type = FieldType.TextArea, label = "Give details", showCondition = "litigation=\"DO_NOT_SHOW\"")
    private final String litigationDetails;

    @CCD(type = FieldType.YesOrNo, label = "Learning disability issues", showCondition = "litigation=\"DO_NOT_SHOW\"", hint = "For example, Respondent has learning disability")
    private final String learningDisability;
    @CCD(type = FieldType.TextArea, label = "Give details", showCondition = "learningDisability=\"DO_NOT_SHOW\"")
    private final String learningDisabilityDetails;
}
