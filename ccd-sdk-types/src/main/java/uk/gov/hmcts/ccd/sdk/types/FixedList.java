package uk.gov.hmcts.ccd.sdk.types;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface FixedList {
    boolean generate() default false;

    String id() default "";
}
