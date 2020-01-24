package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import uk.gov.hmcts.ccd.sdk.types.ComplexType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

import static uk.gov.hmcts.ccd.sdk.types.FieldType.YesOrNo;

@Data
@Builder
@AllArgsConstructor
@ComplexType(name = "RiskAndHarm",
        label = "## Is there evidence of any of the following?", labelId = "evidenceQuestion")
public class Risks {
    @CaseField(type = YesOrNo, label = "Neglect")
    private final String neglect;

    @CaseField(type = YesOrNo, label = "Sexual abuse")
    private final String sexualAbuse;

    @CaseField(type = YesOrNo, label = "Physical harm including non-accidental injury")
    private final String physicalHarm;

    @CaseField(type = YesOrNo, label = "Emotional harm")
    private final String emotionalHarm;

    @CaseField(typeParameter = "PastOrFutureHarmSelect",
            label = "Select all that apply",
            showCondition = "neglect=\"Yes\"")
    private final List<String> neglectOccurrences;

    @CaseField(typeParameter = "PastOrFutureHarmSelect",
            label = "Select all that apply",
            showCondition = "sexualAbuse=\"Yes\"")
    private final List<String> sexualAbuseOccurrences;

    @CaseField(typeParameter = "PastOrFutureHarmSelect",
            label = "Select all that apply",
            showCondition = "physicalHarm=\"Yes\"")
    private final List<String> physicalHarmOccurrences;

    @CaseField(typeParameter = "PastOrFutureHarmSelect",
            label = "Select all that apply",
            showCondition = "emotionalHarm=\"Yes\"")
    private final List<String> emotionalHarmOccurrences;
}
