package uk.gov.hmcts.ccd.sdk.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface ComplexType {

  String name() default "";

  String label() default "";

  String labelId() default "";

  String border() default "";

  String borderId() default "";

  boolean generate() default false;
}
