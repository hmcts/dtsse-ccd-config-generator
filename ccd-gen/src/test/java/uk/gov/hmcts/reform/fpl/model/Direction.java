package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.enums.DirectionAssignee;
import uk.gov.hmcts.reform.fpl.enums.OtherPartiesDirectionAssignee;
import uk.gov.hmcts.reform.fpl.enums.ParentsAndRespondentsDirectionAssignee;
import uk.gov.hmcts.reform.fpl.model.common.Element;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

@Data
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Direction {
    @CaseField(label = " ")
    private final String directionType;
    private String directionText;
    private final String status;
    private DirectionAssignee assignee;
    private ParentsAndRespondentsDirectionAssignee parentsAndRespondentsAssignee;
    private OtherPartiesDirectionAssignee otherPartiesAssignee;
    @CaseField(label = " ")
    private String readOnly;
    @CaseField(label = " ")
    private String directionRemovable;
    @CaseField(label = " ")
    private String directionNeeded;
    private String custom;
    @CaseField(label = "Deadline")
    private LocalDateTime dateToBeCompletedBy;
    private DirectionResponse response;
    private List<Element<DirectionResponse>> responses;

    public List<Element<DirectionResponse>> getResponses() {
        return defaultIfNull(responses, new ArrayList<>());
    }
}
