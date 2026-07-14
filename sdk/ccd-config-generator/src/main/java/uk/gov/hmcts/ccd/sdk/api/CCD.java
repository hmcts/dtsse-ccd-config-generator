package uk.gov.hmcts.ccd.sdk.api;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CCDDefinitions.class)
public @interface CCD {

  /**
   * Optional external CCD identifier. This is independent of the Java/Jackson property name.
   */
  String id() default "";

  /** Optional legacy field identifier used only by field authorisation rows. */
  String authorisationId() default "";

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

  /** Optional state Description when it intentionally differs from the state Name. */
  String description() default "";

  String showCondition() default "";

  String regex() default "";

  int displayOrder() default 0;

  FieldType typeOverride() default FieldType.Unspecified;

  /** External CCD field type name when the type is a service-defined complex type. */
  String typeNameOverride() default "";

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

  /**
   * Emits the Searchable column even when its value is the default {@code Y}.
   */
  boolean includeSearchable() default false;

  int min() default Integer.MIN_VALUE;

  int max() default Integer.MAX_VALUE;

  boolean retainHiddenValue() default false;

  /**
   * Optional legacy RetainHiddenValue spelling, for example {@code Yes}.
   */
  String retainHiddenValueValue() default "";

  /**
   * Optional row-specific live date in CCD's {@code dd/MM/yyyy} format.
   */
  String liveFrom() default "";

  /** Omits the state Description column while retaining its Name. */
  boolean omitDescription() default false;

  /**
   * Typed schema profiles in which this metadata is applicable. Empty means every profile.
   */
  Class<?>[] includeInProfiles() default {};

  /**
   * Typed schema profiles from which this field is excluded.
   */
  Class<?>[] excludeFromProfiles() default {};
}
