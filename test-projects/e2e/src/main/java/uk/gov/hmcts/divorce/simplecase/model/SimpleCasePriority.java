package uk.gov.hmcts.divorce.simplecase.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;

@Getter
@AllArgsConstructor
@ComplexType(name = "SimpleCasePriorityList", generate = true)
public enum SimpleCasePriority implements HasLabel {

    NORMAL("Normal"),
    URGENT("Urgent");

    private final String label;
}
