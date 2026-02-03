package uk.gov.hmcts.ccd.sdk.taskmanagement.model.response;

import java.util.List;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;

public record TaskGetResponse(List<TaskPayload> tasks) {
}
