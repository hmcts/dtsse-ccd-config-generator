package uk.gov.hmcts.reform.fpl.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.type.DynamicElementIndicator;
import uk.gov.hmcts.ccd.sdk.type.DynamicList;
import uk.gov.hmcts.ccd.sdk.type.DynamicListElement;

import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HearingDateDynamicElement implements DynamicElementIndicator {
    private final String date;
    private final UUID id;

    @Override
    public DynamicListElement toDynamicElement() {
        return DynamicListElement.builder().code(id).label(date).build();
    }

    public static HearingDateDynamicElement getHearingDynamicElement(DynamicList list) {
        return HearingDateDynamicElement.builder()
            .date(list.getValue().getLabel())
            .id(list.getValue().getCode())
            .build();
    }
}
