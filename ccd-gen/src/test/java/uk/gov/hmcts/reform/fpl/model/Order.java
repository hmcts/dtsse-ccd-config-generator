package uk.gov.hmcts.reform.fpl.model;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.ComplexType;
import ccd.sdk.types.FieldType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.checkerframework.checker.units.qual.C;
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
    @CaseField(label = "The date of the hearing", type = FieldType.Date)
    private final String hearingDate;
    @CaseField(label = "Directions")
    private final List<Element<Direction>> directions;
    @CaseField(label = "Is this the final version?")
    private final OrderStatus orderStatus;
    @CaseField(label = "File")
    private final DocumentReference orderDoc;
    @CaseField(label = "Judge and legal advisor")
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;
}
