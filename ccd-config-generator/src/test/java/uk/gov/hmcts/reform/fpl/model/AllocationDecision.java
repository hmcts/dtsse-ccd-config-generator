package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import uk.gov.hmcts.ccd.sdk.types.ComplexType;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@ComplexType(labelId = "allocationDecision_Label", label = "The proposed allocation is ${allocationProposal.proposal}.")
public class AllocationDecision {
    @CCD(typeParameter = "AllocationProposalList", showCondition = "allocationProposalPresent!=\"Yes\" OR judgeLevelRadio=\"No\"", label = "Which level of judge is needed for this case?")
    private final String proposal;
    @CCD(type = FieldType.TextArea, showCondition = "allocationProposalPresent!=\"Yes\" OR judgeLevelRadio=\"No\"", label = "Give reason")
    private final String proposalReason;
    @CCD(type = FieldType.YesOrNo, showCondition = "allocationProposalPresent=\"DO_NOT_SHOW\"", label = "Is allocation proposal present?")
    private final String allocationProposalPresent;
    @CCD(type = FieldType.YesOrNo, showCondition = "allocationProposalPresent!=\"No\"", label = "Is this the right level of judge?")
    private final String judgeLevelRadio;
}
