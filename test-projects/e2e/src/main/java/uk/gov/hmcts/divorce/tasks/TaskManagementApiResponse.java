package uk.gov.hmcts.divorce.tasks;

public record TaskManagementApiResponse(Integer statusCode, String body) {
    public boolean isSuccess() {
        return statusCode != null && (statusCode == 200 || statusCode == 201);
    }
}
