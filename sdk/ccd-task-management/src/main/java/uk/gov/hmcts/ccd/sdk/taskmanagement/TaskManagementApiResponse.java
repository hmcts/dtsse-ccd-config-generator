package uk.gov.hmcts.ccd.sdk.taskmanagement;

public record TaskManagementApiResponse(Integer statusCode, String body, TaskCreateResponse task) {
    public boolean isSuccess() {
        return statusCode != null && (statusCode == 200 || statusCode == 201);
    }
}
