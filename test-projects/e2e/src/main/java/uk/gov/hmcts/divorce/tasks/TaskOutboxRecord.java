package uk.gov.hmcts.divorce.tasks;

public record TaskOutboxRecord(long id, String taskId, String payload) {
}
