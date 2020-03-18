package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.model.common.DocumentReference;
import uk.gov.hmcts.reform.fpl.model.common.JudgeAndLegalAdvisor;

@Data
@Builder(toBuilder = true)
public class C21Order {
    @CCD(label = "Order title")
    private final String orderTitle;
    @CCD(label = "Order details")
    private final String orderDetails;
    @CCD(label = "Order document")
    private final DocumentReference document;
    @CCD(label = "Date and time of upload")
    private final String orderDate;
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;
}
