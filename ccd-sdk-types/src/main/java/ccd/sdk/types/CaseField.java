package ccd.sdk.types;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface CaseField {
    String label();
    String hint() default "";
    String showCondition() default "";
    FieldType type() default FieldType.Unspecified;

    String typeParameter() default "";

    boolean showSummaryContent() default false;
}
