package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TypeMapperTest {

  private static final TypeMapper.EnumResolver ENUMS =
      id -> Map.of("ClaimType", "ClaimType").get(id);
  private static final TypeMapper.ComplexResolver COMPLEX =
      id -> Map.of("Party", "Party").get(id);

  private TypeMapper.Mapping map(String type, String param) {
    return TypeMapper.map(type, param, ENUMS, COMPLEX);
  }

  @Test
  void textMapsToStringWithNoOverride() {
    TypeMapper.Mapping mapping = map("Text", null);
    assertThat(mapping.getJavaType()).isEqualTo("String");
    assertThat(mapping.getTypeOverride()).isNull();
  }

  @Test
  void dateAndDateTimeMapToJavaTimeTypes() {
    assertThat(map("Date", null).getJavaType()).isEqualTo("java.time.LocalDate");
    assertThat(map("Date", null).getTypeOverride()).isNull();
    assertThat(map("DateTime", null).getJavaType()).isEqualTo("java.time.LocalDateTime");
  }

  @Test
  void numberMapsToInteger() {
    assertThat(map("Number", null).getJavaType()).isEqualTo("Integer");
    assertThat(map("Number", null).getTypeOverride()).isNull();
  }

  @Test
  void yesOrNoMapsToSdkType() {
    assertThat(map("YesOrNo", null).getJavaType())
        .isEqualTo("uk.gov.hmcts.ccd.sdk.type.YesOrNo");
    assertThat(map("YesOrNo", null).getTypeOverride()).isNull();
  }

  @Test
  void emailAndTextAreaNeedOverrideOnStringCarrier() {
    TypeMapper.Mapping email = map("Email", null);
    assertThat(email.getJavaType()).isEqualTo("String");
    assertThat(email.getTypeOverride()).isEqualTo("Email");

    TypeMapper.Mapping textArea = map("TextArea", null);
    assertThat(textArea.getJavaType()).isEqualTo("String");
    assertThat(textArea.getTypeOverride()).isEqualTo("TextArea");
  }

  @Test
  void fixedRadioListMapsToEnumWithoutOverride() {
    TypeMapper.Mapping mapping = map("FixedRadioList", "ClaimType");
    assertThat(mapping.getJavaType()).isEqualTo("ClaimType");
    assertThat(mapping.getTypeOverride()).isNull();
  }

  @Test
  void fixedListMapsToEnumWithFixedListOverride() {
    TypeMapper.Mapping mapping = map("FixedList", "ClaimType");
    assertThat(mapping.getJavaType()).isEqualTo("ClaimType");
    assertThat(mapping.getTypeOverride()).isEqualTo("FixedList");
    // The FieldTypeParameter must be carried too, otherwise the FixedLists ID linking the
    // field to its enum values is lost from the emitted @CCD annotation.
    assertThat(mapping.getTypeParameterOverride()).isEqualTo("ClaimType");
  }

  @Test
  void multiSelectListMapsToSetOfEnum() {
    TypeMapper.Mapping mapping = map("MultiSelectList", "ClaimType");
    assertThat(mapping.getJavaType()).isEqualTo("java.util.Set<ClaimType>");
    assertThat(mapping.getTypeOverride()).isNull();
  }

  @Test
  void collectionMapsToListOfListValue() {
    TypeMapper.Mapping mapping = map("Collection", "Party");
    assertThat(mapping.getJavaType())
        .isEqualTo("java.util.List<uk.gov.hmcts.ccd.sdk.type.ListValue<Party>>");
  }

  @Test
  void complexMapsToGeneratedType() {
    TypeMapper.Mapping mapping = map("Complex", "Party");
    assertThat(mapping.getJavaType()).isEqualTo("Party");
    assertThat(mapping.getTypeOverride()).isNull();
  }

  @Test
  void complexMapsPredefinedTypeToSdkClass() {
    TypeMapper.Mapping mapping = map("Complex", "AddressUK");
    assertThat(mapping.getJavaType()).isEqualTo("uk.gov.hmcts.ccd.sdk.type.AddressUK");
  }

  @Test
  void predefinedBaseTypeMapsToSdkClass() {
    TypeMapper.Mapping mapping = map("Document", null);
    assertThat(mapping.getJavaType()).isEqualTo("uk.gov.hmcts.ccd.sdk.type.Document");
  }
}
