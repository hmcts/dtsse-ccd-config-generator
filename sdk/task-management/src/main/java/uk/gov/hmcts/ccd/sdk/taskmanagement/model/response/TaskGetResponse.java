package uk.gov.hmcts.ccd.sdk.taskmanagement.model.response;

import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;

import java.util.List;

public record TaskGetResponse(List<TaskPayload> tasks) {
}
