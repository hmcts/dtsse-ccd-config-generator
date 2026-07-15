package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/**
 * A CCD field: either a CaseField row (a member of the generated CaseData class) or a
 * ComplexTypes member (a member of a generated complex type class).
 */
@Value
@Builder(toBuilder = true)
public class FieldModel {

  /** The CCD field ID (CaseField ID or ComplexTypes ListElementCode). */
  String id;

  /**
   * The generated Java member name. When it differs from {@link #id} the emitter adds
   * {@code @JsonProperty(id)} so the SDK still derives the exact CCD ID.
   */
  String javaName;

  /** The input FieldType, e.g. "Text", "FixedList", "Collection". */
  String fieldType;

  /** The input FieldTypeParameter, e.g. a FixedList ID or Collection element type. */
  String fieldTypeParameter;

  /**
   * The Java type to emit, as a source-level reference, e.g. "String",
   * "uk.gov.hmcts.ccd.sdk.type.YesOrNo", "java.util.List&lt;ListValue&lt;Party&gt;&gt;".
   * Chosen so the SDK's inference reproduces the input FieldType exactly; when that is
   * impossible {@link #typeOverride}/{@link #typeParameterOverride} are set instead.
   */
  String javaType;

  /** FieldType enum constant name for @CCD(typeOverride), or null when inference suffices. */
  String typeOverride;

  /** Value for @CCD(typeParameterOverride), or null. */
  String typeParameterOverride;

  String label;
  String hint;
  String showCondition;
  String regex;
  String categoryId;
  Boolean searchable;
  Boolean retainHiddenValue;
  Integer min;
  Integer max;

  /**
   * Names of generated {@code HasAccessControl} classes to reference from {@code @CCD(access)}. The
   * union of these classes' grants reproduces the field's residual AuthorisationCaseField grant (see
   * {@code AccessClassComputer}: a composition of mined groups + atoms, nfdiv-style).
   */
  List<String> accessClassNames;

  /** Overlay tags when the row came from a per-environment overlay file; empty for base. */
  Set<String> overlayTags;

  /**
   * A generation-time environment gate expression ({@code [!]ENV_VAR:value}) emitted as
   * {@code @CCD(gate = ...)}, or null for an ungated field. Set when the field came from a
   * per-environment overlay fragment whose suffix has a configured {@link OverlayCondition}: the
   * field then becomes a real {@code CaseData} member (see {@link #overlayTags}, which is cleared in
   * that case) whose CaseField/AuthorisationCaseField/CaseEventToFields/CaseTypeTab rows the SDK
   * emits only when the gate matches — reproducing the overlay-only field per environment instead of
   * dropping it to passthrough. An overlay row whose suffix has no configured predicate keeps its
   * {@link #overlayTags} and null gate, and still routes to passthrough as before.
   */
  String gate;

  /**
   * True when the input {@code FieldType} is neither an SDK FieldType constant, a generated
   * complex type, a fixed-list enum, nor an SDK-predefined type (e.g. a CCD-platform complex type
   * such as {@code JudicialUser}/{@code CaseQueriesCollection} the SDK has no Java carrier for).
   * The field is generated as {@code String} (FieldType=Text), so the original FieldType is
   * grafted back via an overwrite-column passthrough.
   */
  boolean unknownType;

  /**
   * When non-null, this is a synthetic cluster-parent field emitted as
   * {@code @JsonUnwrapped(prefix = unwrapPrefix)} of the {@link #javaType} complex type. The SDK
   * flattens it back to {@code unwrapPrefix + capitalize(member)} CCD field IDs, so no flat
   * CaseField is emitted for the parent itself. Null for ordinary fields.
   */
  String unwrapPrefix;
}
