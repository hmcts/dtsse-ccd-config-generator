package uk.gov.hmcts.ccd.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures how many times a submitted callback should be attempted.
 * This annotation is supported on CallbackHandlerBean submitted callback implementations only.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Retries {

  int value();
}
