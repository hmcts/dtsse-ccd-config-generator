package ccd.sdk.types;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface CaseField {
    String label();
    String hint() default "";
    FieldType type() default FieldType.Unspecified;

}
