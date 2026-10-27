package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.Set;

/**
 * Decides whether a model property's inferred CCD type ({@link TypeInference}) reproduces the
 * definition's declared {@code FieldType} — the EXACT_MATCH vs TYPE_CONFLICT split. A property that
 * resolves by name always at least matches; the question is only whether the SDK's type inference
 * over the existing Java type would emit the same {@code FieldType} the definition declares.
 */
final class TypeCompatibility {

  /**
   * FieldTypes that the SDK cannot infer from a plain Java type and that a retrofit therefore
   * expresses with {@code @CCD(typeOverride=…)} over a {@code String}/carrier field — so a model
   * {@code String} declaring one of these is <em>compatible</em> once annotated. Mirrors
   * {@code TypeMapper.STRING_OVERRIDE_TYPES} plus the leaf FieldTypes carried as String.
   */
  private static final Set<String> STRING_CARRIED_TYPES = Set.of(
      "TextArea", "Email", "PhoneUK", "Schedule", "MoneyGBP", "Text");

  private TypeCompatibility() {
  }

  /**
   * Whether the model property is type-compatible with the definition field.
   *
   * @param definitionFieldType the definition's {@code FieldType} column
   * @param definitionFieldTypeParameter the definition's {@code FieldTypeParameter} column, or null
   * @param inferred the model property's inferred CCD type
   * @return true for EXACT_MATCH, false for TYPE_CONFLICT
   */
  static boolean compatible(String definitionFieldType, String definitionFieldTypeParameter,
      TypeInference.Inferred inferred) {
    String defType = definitionFieldType == null ? "Text" : definitionFieldType.trim();

    // A concrete collection wrapper always needs a typeParameterOverride, so it is a conflict even
    // when the FieldType (Collection) matches — the SDK would emit the wrong FieldTypeParameter.
    if (inferred.collection && inferred.concreteWrapper) {
      return false;
    }

    if ("Collection".equals(defType) || "MultiSelectList".equals(defType)) {
      if (!inferred.collection) {
        return false;
      }
      // Element parameter must agree (or the definition left it open).
      return parameterAgrees(definitionFieldTypeParameter, inferred.fieldTypeParameter);
    }

    // Text/String-carried leaf types: a model String is compatible (annotate with typeOverride).
    if (STRING_CARRIED_TYPES.contains(defType) && "Text".equals(inferred.fieldType)) {
      return true;
    }

    // FixedList/FixedRadioList: a model enum infers to FixedRadioList; both list flavours are
    // reachable from that enum via a typeOverride, so an enum-backed field is compatible.
    if (("FixedList".equals(defType) || "FixedRadioList".equals(defType))
        && "FixedRadioList".equals(inferred.fieldType)) {
      return true;
    }

    // Otherwise the inferred FieldType must equal the definition's exactly (Text==Text, Date==Date,
    // YesOrNo==YesOrNo, Number==Number, or a complex type whose name matches the parameter/type).
    if (defType.equals(inferred.fieldType)) {
      return parameterAgrees(definitionFieldTypeParameter, inferred.fieldTypeParameter);
    }

    // Complex field: the definition carries FieldType=Complex + FieldTypeParameter=<TypeId>, while
    // the SDK infers the type's own name as the FieldType. Treat a name match as compatible.
    if ("Complex".equals(defType) && definitionFieldTypeParameter != null
        && definitionFieldTypeParameter.equals(inferred.fieldType)) {
      return true;
    }

    // A definition field whose FieldType is itself the complex type name (some definitions name the
    // type directly) matches when inference produced the same type name.
    return defType.equals(inferred.fieldType);
  }

  private static boolean parameterAgrees(String definitionParam, String inferredParam) {
    if (definitionParam == null || definitionParam.isBlank()) {
      return true;
    }
    return definitionParam.equals(inferredParam);
  }
}
