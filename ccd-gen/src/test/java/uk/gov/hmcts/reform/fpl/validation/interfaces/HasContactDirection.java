package uk.gov.hmcts.reform.fpl.validation.interfaces;

import uk.gov.hmcts.reform.fpl.validation.validators.HasContactDirectionValidator;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = { HasContactDirectionValidator.class })
public @interface HasContactDirection {
    String message() default "Enter the contact's full name";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
