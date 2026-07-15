package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Value;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

/**
 * Maps a CCD input FieldType (plus its FieldTypeParameter) onto the Java type the emitter
 * should declare, so the SDK's own type inference reproduces the original FieldType.
 *
 * <p>Where a Java type exists whose inference yields the target FieldType (for example
 * {@code String} yields {@code Text}, {@code java.time.LocalDate} yields {@code Date}, an enum
 * yields {@code FixedRadioList}), the mapper returns that type with no override. Where no such
 * Java type exists (for example {@code Email}, {@code TextArea}, {@code PhoneUK}), the mapper
 * returns a carrier Java type together with a {@code typeOverride} naming the FieldType enum
 * constant, which the emitter renders as {@code @CCD(typeOverride = FieldType.X)}.
 */
final class TypeMapper {

  private static final String SDK_TYPE_PKG = "uk.gov.hmcts.ccd.sdk.type.";
  private static final String LIST_VALUE = SDK_TYPE_PKG + "ListValue";

  /**
   * FieldTypes the SDK cannot infer from a plain Java type and that therefore always need a
   * {@code typeOverride}, carried as a {@code String} member.
   */
  private static final Set<String> STRING_OVERRIDE_TYPES = Set.of(
      "TextArea", "Email", "PhoneUK", "Schedule", "MoneyGBP");

  /** The FieldType enum constant names the SDK actually defines. */
  private static final Set<String> FIELD_TYPE_CONSTANTS =
      Arrays.stream(FieldType.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

  private TypeMapper() {
  }

  /**
   * Maps a field's type to the Java declaration and any @CCD type overrides.
   *
   * @param fieldType the input FieldType column value
   * @param fieldTypeParameter the input FieldTypeParameter, or null
   * @param enumTypeResolver resolves a FixedList/MultiSelectList ID to its generated enum name
   * @param complexTypeResolver resolves a complex-type ID to its Java type reference
   * @return the resolved Java type and overrides
   */
  static Mapping map(
      String fieldType,
      String fieldTypeParameter,
      EnumResolver enumTypeResolver,
      ComplexResolver complexTypeResolver) {
    String type = fieldType == null ? "" : fieldType.trim();
    return switch (type) {
      case "Text" -> plain("String");
      case "Date" -> plain("java.time.LocalDate");
      case "DateTime" -> plain("java.time.LocalDateTime");
      case "Number" -> plain("Integer");
      case "YesOrNo" -> plain(SDK_TYPE_PKG + "YesOrNo");
      case "Label" -> override("String", "Label", null);
      case "FixedList", "FixedRadioList" -> fixedList(type, fieldTypeParameter, enumTypeResolver);
      case "MultiSelectList" -> multiSelect(fieldTypeParameter, enumTypeResolver);
      case "Collection" -> collection(fieldTypeParameter, complexTypeResolver);
      case "Complex" -> complex(fieldTypeParameter, complexTypeResolver);
      default -> mapOther(type, fieldTypeParameter, complexTypeResolver);
    };
  }

  private static Mapping mapOther(
      String type, String fieldTypeParameter, ComplexResolver complexTypeResolver) {
    // A complex type the definition actually declares (its ID appears on the ComplexTypes sheet)
    // wins over a same-named leaf FieldType constant: fpl declares a complex type named "Schedule"
    // with members, which is NOT the SDK's leaf FieldType.Schedule. Resolve the complex type first
    // so the field is wired to the generated class (and its ComplexTypes rows round-trip) rather
    // than carried as a String override that would drop the members.
    String resolved = complexTypeResolver.resolve(type);
    if (resolved != null) {
      return plain(resolved);
    }
    if (STRING_OVERRIDE_TYPES.contains(type)) {
      return override("String", type, null);
    }
    if (SdkPredefinedTypes.isPredefined(type)) {
      return plain(SdkPredefinedTypes.javaTypeFor(type));
    }
    if (FIELD_TYPE_CONSTANTS.contains(type)) {
      // A real FieldType enum constant with no dedicated Java carrier (OrderSummary,
      // CaseLocation, ComponentLauncher, CasePaymentHistoryViewer, …): carry as String with
      // an override naming the constant.
      return override("String", type, fieldTypeParameter);
    }
    // An unknown/custom type that is neither a generated complex type, an SDK predefined type,
    // nor a real FieldType constant (e.g. CaseHistoryViewer, WaysToPay). Emitting
    // FieldType.<type> would not compile, so carry it as a plain String and flag it unknown so
    // the linker records a gap and passes the original FieldType through as raw JSON.
    return Mapping.unknown("String");
  }

  private static Mapping fixedList(
      String type, String fieldTypeParameter, EnumResolver enumTypeResolver) {
    String enumType = enumTypeResolver.resolve(fieldTypeParameter);
    if (enumType != null) {
      // An enum member infers to FixedRadioList. When the input is FixedList we still emit the
      // enum but force the FieldType back to FixedList via an override; the FieldTypeParameter
      // must also be carried as a typeParameterOverride, otherwise the FixedLists ID linking
      // the field to its enum values is silently lost.
      return "FixedList".equals(type)
          ? new Mapping(enumType, "FixedList", fieldTypeParameter, false)
          : plain(enumType);
    }
    return new Mapping("String", type, fieldTypeParameter, false);
  }

  private static Mapping multiSelect(String fieldTypeParameter, EnumResolver enumTypeResolver) {
    String enumType = enumTypeResolver.resolve(fieldTypeParameter);
    if (enumType != null) {
      return plain("java.util.Set<" + enumType + ">");
    }
    return new Mapping("String", "MultiSelectList", fieldTypeParameter, false);
  }

  private static Mapping collection(
      String fieldTypeParameter, ComplexResolver complexTypeResolver) {
    String element = collectionElement(fieldTypeParameter, complexTypeResolver);
    return plain("java.util.List<" + LIST_VALUE + "<" + element + ">>");
  }

  private static String collectionElement(
      String fieldTypeParameter, ComplexResolver complexTypeResolver) {
    if (fieldTypeParameter == null || fieldTypeParameter.isBlank()) {
      return "String";
    }
    if (SdkPredefinedTypes.isPredefined(fieldTypeParameter)) {
      return SdkPredefinedTypes.javaTypeFor(fieldTypeParameter);
    }
    String resolved = complexTypeResolver.resolve(fieldTypeParameter);
    if (resolved != null) {
      return resolved;
    }
    return switch (fieldTypeParameter) {
      case "Text" -> "String";
      case "Date" -> "java.time.LocalDate";
      case "DateTime" -> "java.time.LocalDateTime";
      case "Document" -> SDK_TYPE_PKG + "Document";
      default -> "String";
    };
  }

  private static Mapping complex(
      String fieldTypeParameter, ComplexResolver complexTypeResolver) {
    if (fieldTypeParameter != null && SdkPredefinedTypes.isPredefined(fieldTypeParameter)) {
      return plain(SdkPredefinedTypes.javaTypeFor(fieldTypeParameter));
    }
    String resolved = fieldTypeParameter == null
        ? null
        : complexTypeResolver.resolve(fieldTypeParameter);
    if (resolved != null) {
      return plain(resolved);
    }
    return new Mapping("String", "Complex", fieldTypeParameter, false);
  }

  private static Mapping plain(String javaType) {
    return new Mapping(javaType, null, null, false);
  }

  private static Mapping override(String javaType, String typeOverride, String typeParameter) {
    return new Mapping(javaType, typeOverride, typeParameter, false);
  }

  /** Resolves a FixedList/MultiSelectList parameter ID to its generated enum type reference. */
  interface EnumResolver {

    /**
     * Resolves a list ID to the generated enum's Java type reference.
     *
     * @param listId the FieldTypeParameter naming a FixedLists ID
     * @return the enum type reference, or null when unknown
     */
    String resolve(String listId);
  }

  /** Resolves a complex-type ID to the generated (or predefined) Java type reference. */
  interface ComplexResolver {

    /**
     * Resolves a complex-type ID to its Java type reference.
     *
     * @param complexId the complex-type ID
     * @return the Java type reference, or null when unknown
     */
    String resolve(String complexId);
  }

  /** The resolved Java declaration for a field and any @CCD type overrides. */
  @Value
  static class Mapping {

    /** The Java type reference to declare, e.g. "String" or "java.time.LocalDate". */
    String javaType;

    /** FieldType enum constant for @CCD(typeOverride), or null when inference suffices. */
    String typeOverride;

    /** Value for @CCD(typeParameterOverride), or null. */
    String typeParameterOverride;

    /**
     * Whether the input FieldType could not be represented at all (no Java carrier, not a real
     * FieldType constant). The field is emitted as a plain String and the linker records a gap
     * and passes the original FieldType through as raw JSON.
     */
    boolean unknownType;

    static Mapping unknown(String javaType) {
      return new Mapping(javaType, null, null, true);
    }
  }
}
