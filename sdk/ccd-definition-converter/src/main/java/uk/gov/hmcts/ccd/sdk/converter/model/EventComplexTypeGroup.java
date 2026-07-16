package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * A resolved {@code CaseEventToComplexTypes} group: every derivable per-member event override for
 * one complex field on one event, ready to emit as a {@code .complex(CaseData::getField) … .done()}
 * builder block instead of a row-level passthrough.
 *
 * <p>Only groups whose every row's dotted {@code ListElementCode} resolves cleanly through the
 * typed complex-type graph to a leaf getter (see {@code EventComplexTypeResolver}) become a group;
 * anything that does not (unknown member, unreachable type, collection/predefined hop the converter
 * cannot express, {@code DisplayContext=COMPLEX} intermediate rows, an {@code ID}-collision) stays a
 * row-level passthrough. The non-derivable columns the SDK generator cannot compute — the row's
 * {@code ID} (the declaring complex type, arbitrary author data), its {@code FieldDisplayOrder}
 * (the SDK numbers members by a per-event counter, not the input's per-field restart) and any
 * exotic tail — are grafted back over the generated rows via a companion column passthrough (see
 * {@code DefaultDefinitionLinker.buildEventToComplexTypesPassthrough}).
 */
@Value
@Builder
public class EventComplexTypeGroup {

  /**
   * The owning event ID.
   */
  String eventId;

  /**
   * The complex field's CCD ID (the {@code CaseFieldID} column).
   */
  String caseFieldId;

  /**
   * The {@code CaseData} getter for the complex field, e.g.
   * {@code getChangeOrganisationRequestField}.
   */
  String rootGetter;

  /**
   * The element type of the root field when it is a {@code Collection}
   * ({@code List<ListValue<Element>>}), or null when the root is a scalar complex field. When
   * non-null the emitter opens the root scope with the two-arg element-typed
   * {@code .complex(rootGetter, Element.class)} overload (a one-arg {@code .complex(rootGetter)}
   * would type the member builder on the {@code List} and not compile); when null it uses the plain
   * one-arg {@code .complex(rootGetter)}.
   */
  TypeRef rootElementType;

  /**
   * The per-member overrides, in input {@code FieldDisplayOrder} order.
   */
  List<Member> members;

  /**
   * One resolved per-member override — the typed getter chain from the complex field down to the
   * member, plus the member's event-scoped display metadata.
   */
  @Value
  @Builder(toBuilder = true)
  public static class Member {

    /**
     * The {@code .complex(...)} hops between the root complex field and the leaf member's declaring
     * type. Empty for a direct member of the root type. Each hop's getter is invoked on the previous
     * type ({@code rootGetter}'s type for the first hop).
     */
    List<Hop> hops;

    /**
     * The declaring type of the leaf member (the type the {@link #leafGetter} is invoked on).
     */
    TypeRef leafType;

    /**
     * The leaf member getter, e.g. {@code getOrganisationId}.
     */
    String leafGetter;

    /**
     * {@code optional} / {@code mandatory} / {@code readonly} — the builder method for the row's
     * {@code DisplayContext}.
     */
    String contextMethod;

    /**
     * {@code FieldShowCondition}, or null.
     */
    String showCondition;

    /**
     * {@code EventElementLabel}, or null.
     */
    String eventLabel;

    /**
     * {@code EventHintText}, or null.
     */
    String eventHint;

    /**
     * {@code PageID}, or null.
     */
    String pageId;

    /**
     * The generated leaf member's declared {@code @CCD(hint)}, which the SDK's
     * {@code CaseEventToComplexTypesGenerator} cascades onto the row's {@code HintText} unless the
     * member placement overrides it. Carried so the linker can compare it with the input row's
     * {@code HintText} and pick the {@link #hintOverridden} disposition; not itself emitted by the
     * member chain.
     */
    String declaredHint;

    /**
     * Whether the member placement must override the cascaded {@code HintText} with the fluent
     * {@code .hintText(...)} / {@code .noHintText()} setters, rather than letting the leaf member's
     * declared {@code @CCD(hint)} cascade. False when the input row's {@code HintText} already equals
     * the declared hint (including both absent), so the cascade reproduces the row and no setter is
     * emitted; true otherwise. See {@link #hintText}.
     */
    boolean hintOverridden;

    /**
     * The {@code HintText} value to emit when {@link #hintOverridden} is true: a non-null value maps
     * to {@code .hintText(value)} (the input row carries a {@code HintText} differing from the
     * declared hint), and null maps to {@code .noHintText()} (the input row carries no {@code HintText}
     * but the leaf member declares one that would otherwise cascade). Ignored when
     * {@link #hintOverridden} is false.
     */
    String hintText;
  }

  /**
   * One {@code .complex(...)} hop: the type the getter is invoked on, plus the getter.
   */
  @Value
  @Builder
  public static class Hop {

    /**
     * The type the {@link #getter} is invoked on.
     */
    TypeRef declaringType;

    /**
     * The getter for the nested complex member.
     */
    String getter;

    /**
     * The element type of this hop's member when it is a {@code Collection}
     * ({@code List<ListValue<Element>>}), or null when the hop's member is a scalar nested complex
     * field. When non-null the emitter opens this hop's scope with the two-arg element-typed
     * {@code .complex(getter, Element.class)} overload; when null it uses the plain one-arg
     * {@code .complex(getter)}.
     */
    TypeRef elementType;
  }

  /**
   * A reference to a complex type the emitter must name in a {@code $T::$L} method reference: a
   * generated complex type in the model package (identified by its simple class name) or an
   * SDK-predefined type (identified by its fully-qualified name).
   */
  @Value
  @Builder
  public static class TypeRef {

    /**
     * Fully-qualified name for a predefined SDK type, else null (use {@link #simpleName}).
     */
    String predefinedFqn;

    /**
     * Simple class name for a generated complex type in the model package, else null.
     */
    String simpleName;
  }
}
