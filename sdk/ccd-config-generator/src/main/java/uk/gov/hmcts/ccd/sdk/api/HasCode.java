package uk.gov.hmcts.ccd.sdk.api;

/** Supplies an external CCD code independently of a Java enum constant name. */
public interface HasCode {
  String getCode();
}
