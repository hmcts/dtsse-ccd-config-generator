package uk.gov.hmcts.ccd.sdk;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Marker annotation used by the decentralised runtime to indicate that a CCD field
 * should be treated as external-facing only and stripped from the main case_data json blob.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface External {
}
