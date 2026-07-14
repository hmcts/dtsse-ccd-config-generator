package uk.gov.hmcts.ccd.sdk.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

@Retention(RetentionPolicy.RUNTIME)
public @interface CCD {

  /**
   * Primary human readable description field. This property will populate different fields in different contexts:
   * - FixedLists: ListElement
   * - Fields: Label
   * - CaseRoles: Name
   * - States: Name and Description
   */
  String label() default "";

  /**
   * Secondary human readable description field. This property will populate different fields in different contexts:
   * - FixedLists: ListElementCode
   * - Fields: HintText
   * - CaseRoles: Description
   * - States: TitleDisplay
   */
  String hint() default "";

  String showCondition() default "";

  String regex() default "";

  int displayOrder() default 0;

  FieldType typeOverride() default FieldType.Unspecified;

  String typeParameterOverride() default "";

  String categoryID() default "";

  /**
   * Access policies for a field, enum constant or class. Access declared on a class is the default for its fields.
   */
  Class<? extends HasAccessControl>[] access() default {};

  /**
   * Whether access policies inherit defaults from the containing class or an enclosing unwrapped field. Set to false
   * with no {@link #access()} values to opt out of inherited access entirely.
   */
  boolean inheritAccessFromParent() default true;

  boolean showSummaryContent() default false;

  boolean ignore() default false;

  boolean searchable() default true;

  int min() default Integer.MIN_VALUE;

  int max() default Integer.MAX_VALUE;

  boolean retainHiddenValue() default false;
}
