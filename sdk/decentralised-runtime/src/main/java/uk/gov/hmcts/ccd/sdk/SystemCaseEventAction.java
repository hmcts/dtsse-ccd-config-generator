package uk.gov.hmcts.ccd.sdk;

@FunctionalInterface
public interface SystemCaseEventAction<T, S> {

  SystemCaseEventOutcome<S> execute(SystemCaseEventContext<T, S> context);
}
