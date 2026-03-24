package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

public interface TaskSearchParameter<T> {

  TaskSearchKey getKey();

  TaskSearchOperator getOperator();

  T getValues();
}
