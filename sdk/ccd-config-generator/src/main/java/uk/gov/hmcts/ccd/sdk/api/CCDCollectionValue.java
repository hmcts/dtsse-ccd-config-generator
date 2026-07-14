package uk.gov.hmcts.ccd.sdk.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an application-specific CCD collection item wrapper and identifies the property holding
 * the collection's CCD value type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CCDCollectionValue {
  String value() default "value";
}
