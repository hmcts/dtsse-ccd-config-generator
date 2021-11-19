package uk.gov.hmcts.ccd.sdk.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

@Retention(RetentionPolicy.RUNTIME)
public @interface CCD {

  String name() default "";

  String label() default "";

  String hint() default "";

  String showCondition() default "";

  String regex() default "";

  String displayOrder() default "";

  FieldType typeOverride() default FieldType.Unspecified;

  String typeParameterOverride() default "";

  Class<? extends HasAccessControl>[] access() default {};

  boolean showSummaryContent() default false;

  boolean ignore() default false;

}
