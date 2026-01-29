package uk.gov.hmcts.ccd.sdk.taskmanagement.model.request;

import lombok.Builder;

import java.util.List;

@Builder
public record TaskTerminationRequest(String action, List<String> taskIds) {
}
