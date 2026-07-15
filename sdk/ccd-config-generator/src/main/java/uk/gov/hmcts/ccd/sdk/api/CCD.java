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
   * - States: Name, and Description too unless {@link #description()} is set
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

  /**
   * The state's {@code Description} column, when it needs to differ from {@link #label()} (the
   * state's {@code Name}). Only meaningful on a {@code State} enum constant; empty (the default)
   * keeps today's behaviour of {@code Description == Name}.
   */
  String description() default "";

  String showCondition() default "";

  String regex() default "";

  int displayOrder() default 0;

  FieldType typeOverride() default FieldType.Unspecified;

  String typeParameterOverride() default "";

  String categoryID() default "";

  Class<? extends HasAccessControl>[] access() default {};

  boolean inheritAccessFromParent() default true;

  boolean showSummaryContent() default false;

  boolean ignore() default false;

  /**
   * A generation-time environment gate: when set, this field is part of the generated definition
   * only when the predicate matches at the moment {@code generateCCDConfig} runs. Empty (the
   * default) means the field is always emitted.
   *
   * <p>The grammar is {@code [!]ENV_VAR:value} (e.g. {@code CCD_DEF_JO:true} or
   * {@code !CCD_DEF_ENV:prod}); the variable is resolved from {@link System#getProperty(String)}
   * first, then the process environment. When the gate does not match the field behaves exactly as
   * {@code ignore = true}: no CaseField row, no AuthorisationCaseField rows, no CaseEventToFields
   * placement on any event, no CaseTypeTab/search rows, and it is excluded from complex-type member
   * emission and from complex-type reachability (a complex type reached only through gated-off
   * fields produces no ComplexTypes rows). The Java member always exists, so a typed getter used to
   * place the field on an event still compiles; only the emitted rows are gated.
   *
   * <p>This mirrors the per-environment overlay fragments hand-written definitions activate by
   * glob inclusion/exclusion when building each environment's spreadsheet — the field lives only in
   * that environment's definition, not in a base row shared by all environments.
   */
  String gate() default "";

  boolean searchable() default true;

  int min() default Integer.MIN_VALUE;

  int max() default Integer.MAX_VALUE;

  boolean retainHiddenValue() default false;
}
