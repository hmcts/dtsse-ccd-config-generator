package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import uk.gov.hmcts.ccd.sdk.types.ComplexType;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.enums.OrderStatus;
import uk.gov.hmcts.reform.fpl.model.common.DocumentReference;
import uk.gov.hmcts.reform.fpl.model.common.Element;
import uk.gov.hmcts.reform.fpl.model.common.JudgeAndLegalAdvisor;

import java.util.List;

@Data
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@ComplexType(name = "StandardDirectionOrder")
public class Order {
    @CCD(label = "The date of the hearing", type = FieldType.Date)
    private final String hearingDate;
    @CCD(label = "Directions")
    private final List<Element<Direction>> directions;
    @CCD(label = "Is this the final version?")
    private final OrderStatus orderStatus;
    @CCD(label = "File")
    private final DocumentReference orderDoc;
    @CCD(label = "Judge and legal advisor")
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;
}
