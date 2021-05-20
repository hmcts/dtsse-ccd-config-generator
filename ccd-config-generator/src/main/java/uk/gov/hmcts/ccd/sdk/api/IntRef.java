package uk.gov.hmcts.ccd.sdk.api;

public class IntRef {
  private int num;

  public int increment() {
    return ++num;
  }

  public int get() {
    return num;
  }

}
