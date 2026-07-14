package uk.gov.hmcts.ccd.sdk.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Container for profile-specific {@link CCD} metadata.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface CCDDefinitions {
  CCD[] value();
}
