package uk.gov.hmcts.ccd.sdk.api;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CCD.MemberOverrides.class)
public @interface CCD {

  /**
   * The name of an inherited field this annotation configures, when the annotation is placed on a
   * CLASS rather than on a field. Empty (the default) is the ordinary field-level form.
   *
   * <p>A field declared once on a shared superclass is one Java member but several CCD members: it
   * emits a row under every complex type that reaches it, and a hand-written definition is free to
   * give those rows different metadata, or to omit some of them. sscs's abstract {@code Entity}
   * declares {@code identity}/{@code name}/{@code address}/{@code contact}/{@code organisation} for
   * {@code Appellant}, {@code Appointee}, {@code OtherParty}, {@code Representative} and
   * {@code JointParty} alike, and the definition puts {@code FieldShowCondition} on
   * {@code representative}'s five rows only. A field-level annotation cannot say that — it says one
   * thing for every subclass at once.
   *
   * <p>Placed on the subclass, this annotation says it for that subclass alone: it REPLACES the
   * inherited field's own {@code @CCD} wherever rows are produced through this class (its
   * {@code ComplexTypes} members, its {@code CaseField} rows when reached
   * {@code @JsonUnwrapped}, and the access those rows derive), leaving every other subclass on the
   * field's own declaration. {@code ignore = true} in this form drops the member from this class
   * only — the shape {@code JointParty} needs, whose inherited {@code Party} members the definition
   * has no fields for.
   *
   * <p>It configures an inherited member, not a declared one: a class can always annotate its own
   * field directly. Naming a field the class declares itself, or one no supertype declares, is a
   * configuration error and fails generation rather than silently doing nothing.
   */
  String member() default "";

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

  /**
   * The class that declares the type {@link #typeParameterOverride()} names, when no field in the
   * model <em>declares</em> that type. Setting it makes the class part of the generated definition:
   * it is reached by complex-type resolution exactly as a declared field type is, so an enum emits
   * its {@code FixedLists} rows and a class its {@code ComplexTypes} rows. {@link Void} (the
   * default) means the field reaches no additional type.
   *
   * <p>Needed because {@code typeParameterOverride} is only a string written into the
   * {@code FieldTypeParameter} column, while {@code FixedLists}/{@code ComplexTypes} rows come from
   * reflection over the types reachable from the case-data class. A field declared as a
   * {@code String} carrying {@code typeParameterOverride} therefore emits a
   * {@code FieldTypeParameter} referencing a list the definition never declares — the reference is
   * there but the rows behind it are not.
   *
   * <p>This is the shape a large reference-data list takes in a hand-written definition: the field
   * is a {@code String} because no team would spell 160-odd venue codes as an enum, so the list
   * exists only in the definition. Naming the enum here keeps the field's declared type — and hence
   * every caller and serialised payload — untouched, while the enum supplies the rows and, through
   * {@code @ComplexType(name)}, the list ID.
   *
   * <p>The class is reached only when the field itself is part of the definition: an
   * {@code ignore = true}, {@code @JsonIgnore} or gated-off field reaches nothing, exactly as it
   * contributes no rows of its own.
   */
  Class<?> typeParameterClass() default Void.class;

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

  /**
   * Container for repeated class-level {@link CCD} annotations, so one class can override several
   * inherited members. Never written by hand — {@code @Repeatable} makes the compiler produce it.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @interface MemberOverrides {
    CCD[] value();
  }
}
