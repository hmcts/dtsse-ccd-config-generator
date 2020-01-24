package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.model.common.DocumentReference;
import uk.gov.hmcts.reform.fpl.model.common.JudgeAndLegalAdvisor;

@Data
@Builder(toBuilder = true)
public class C21Order {
    @CaseField(label = "Order title")
    private final String orderTitle;
    @CaseField(label = "Order details")
    private final String orderDetails;
    @CaseField(label = "Order document")
    private final DocumentReference document;
    @CaseField(label = "Date and time of upload")
    private final String orderDate;
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;
}
