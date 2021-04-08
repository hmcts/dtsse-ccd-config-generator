package uk.gov.hmcts.ccd.sdk.api;

@ComplexType(generate = false)
public interface HasRole {

  String getRole();

  String getCaseTypePermissions();
}
