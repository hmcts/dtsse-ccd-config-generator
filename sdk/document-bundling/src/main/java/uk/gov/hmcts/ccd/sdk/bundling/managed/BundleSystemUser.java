package uk.gov.hmcts.ccd.sdk.bundling.managed;

/** Supplies the existing application's cached system-user token to the standard CDAM adapter. */
@FunctionalInterface
public interface BundleSystemUser {
  String authorisation();
}
