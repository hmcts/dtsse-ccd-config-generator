package uk.gov.hmcts.ccd.sdk.taskmanagement.model.request;

import java.util.List;
import lombok.Builder;

@Builder
public record TaskTerminationRequest(String action, List<String> taskIds) {
}
