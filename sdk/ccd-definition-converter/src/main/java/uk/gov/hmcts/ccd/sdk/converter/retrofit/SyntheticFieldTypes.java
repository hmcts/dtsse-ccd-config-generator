package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * The Java type a synthesised definition-only field (proposal decision 4) is declared with on the
 * team's model class.
 *
 * <p>The linker already computed a source-level {@link FieldModel#getJavaType()} chosen so the SDK's
 * inference reproduces the definition's {@code FieldType} — {@code String} for a Text/Email/…
 * carrier (the {@code typeOverride} on the rendered {@code @CCD} restores the exact type),
 * {@code java.time.LocalDate}/{@code LocalDateTime}, {@code Integer}, a fully-qualified SDK type
 * ({@code uk.gov.hmcts.ccd.sdk.type.YesOrNo}, {@code AddressUK}, …), or a
 * {@code java.util.List<uk.gov.hmcts.ccd.sdk.type.ListValue<X>>} collection. Those are all valid on
 * an arbitrary team class, so the synthesised field simply declares that type verbatim.
 */
final class SyntheticFieldTypes {

  private SyntheticFieldTypes() {
  }

  /**
   * The Java type source string for a synthesised field.
   *
   * @param field the definition-only field model
   * @return the source-level Java type
   */
  static String javaType(FieldModel field) {
    String javaType = field.getJavaType();
    return javaType == null || javaType.isBlank() ? "String" : javaType;
  }
}
