package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
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
    @CaseField(type = FieldType.YesOrNo, label = "Spoken or written Welsh")
    private final String welsh;
    @CaseField(type = FieldType.YesOrNo, label = "Interpreter", hint = "Including sign language")
    private final String interpreter;
    @CaseField(type = FieldType.YesOrNo, label = "Intermediary", hint = "Including sign language")
    private final String intermediary;
    @CaseField(type = FieldType.TextArea, label = "Give details", showCondition = "welsh=\"Yes\"")
    private final String welshDetails;
    @CaseField(type = FieldType.TextArea, label = "Give details including person, language and dialect", showCondition = "interpreter=\"Yes\"")
    private final String interpreterDetails;
    @CaseField(type = FieldType.YesOrNo, label = "Facilities or assistance for a disability")
    private final String disabilityAssistance;
    @CaseField(type = FieldType.TextArea, label = "Give details", showCondition = "intermediary=\"Yes\"")
    private final String intermediaryDetails;
    @CaseField(type = FieldType.YesOrNo, label = "Separate waiting room or other security measures", hint = "For example, mother and father need to be in separate waiting rooms as history of domestic violence")
    private final String extraSecurityMeasures;
    @CaseField(type = FieldType.TextArea, label = "Give details", showCondition = "extraSecurityMeasures=\"Yes\"")
    private final String extraSecurityMeasuresDetails;
    @CaseField(type = FieldType.TextArea, label = "Give details", showCondition = "disabilityAssistance=\"Yes\"")
    private final String disabilityAssistanceDetails;
    @CaseField(type = FieldType.YesOrNo, label = "Something else", hint = "For example, mother and father need to be in separate waiting rooms as history of domestic violence")
    private final String somethingElse;
    @CaseField(type = FieldType.TextArea, label = "Give details", showCondition = "somethingElse=\"Yes\"")
    private final String somethingElseDetails;

    @CaseField(type = FieldType.YesOrNo, label = "Litigation capacity issues", showCondition = "litigation=\"DO_NOT_SHOW\"")
    private final String litigation;
    @CaseField(type = FieldType.TextArea, label = "Give details", showCondition = "litigation=\"DO_NOT_SHOW\"")
    private final String litigationDetails;

    @CaseField(type = FieldType.YesOrNo, label = "Learning disability issues", showCondition = "litigation=\"DO_NOT_SHOW\"", hint = "For example, Respondent has learning disability")
    private final String learningDisability;
    @CaseField(type = FieldType.TextArea, label = "Give details", showCondition = "learningDisability=\"DO_NOT_SHOW\"")
    private final String learningDisabilityDetails;
}
