package uk.gov.hmcts;

public class FailedTestException extends RuntimeException {
    public FailedTestException(String msg) {
        super(msg);
    }
}
