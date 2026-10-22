package uk.gov.hmcts.ccd.sdk.generator;

import org.junit.Ignore;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ComplexTypeGeneratorTest {

  public static class CCDDisplayOrderClass {
    @CCD(displayOrder = 2)
    private String stringField2;

    @CCD(displayOrder = 6)
    private String stringField6;

    @CCD(displayOrder = 4)
    private String stringField4;

    @CCD(label = "No DisplayOrder")
    private String displayOrder;

    @CCD(displayOrder = 3)
    private String stringField3;

    @CCD(displayOrder = 5)
    private String stringField5;

    @CCD(displayOrder = 1)
    private String stringField1;
  }

  public static class CCDInvalidClass {
    @CCD(displayOrder = -1)
    private String stringField1;
  }

  public static class CCDNoDisplayOrderClass {
    @CCD(label = "Label2")
    private String stringField1;

    @CCD(label = "Label3")
    private String stringField2;

    @CCD(label = "Label1")
    private String stringField3;
  }

  public static class CCDDisplayContextParameterClass {
    @CCD(label = "Confidentiality Confirmed",
        displayContextParameter = "#DATETIMEDISPLAY(d MMM yyyy, h:mm:ss a)")
    private String confirmedDate;

    @CCD(label = "Plain")
    private String plain;
  }

  @ComplexType(name = "jointPartyName")
  public static class NamedCompanion {
    private String title;
  }

  @ComplexType(name = "hearingVenueEpimsId")
  public static class NamedElementCompanion {
    private String value;
  }

  public static class CCDNamedComplexTypeClass {
    // The model declares this as one class while the definition addresses it with a DIFFERENT complex
    // type — sscs's JointParty.name, declared Name (itself the model class for the definition's own
    // `name` type) but addressed by `jointPartyName`. One class carries one @ComplexType(name) and
    // typeOverride takes a FieldType constant, which a definition ID is not, so naming the class is the
    // only expression of it that leaves the declared type — and every caller — untouched.
    @CCD(typeParameterClass = NamedCompanion.class)
    private CCDDisplayOrderClass name;

    // On a collection the named class is the ELEMENT type, so it supplies the FieldTypeParameter and the
    // FieldType stays Collection — sscs's OverrideFields.hearingVenueEpimsIds.
    @CCD(typeParameterClass = NamedElementCompanion.class)
    private List<String> epimsIds;

    // typeOverride short-circuits type resolution entirely, so the named class must not move the type.
    @CCD(typeOverride = FieldType.Text, typeParameterClass = NamedCompanion.class)
    private CCDDisplayOrderClass overridden;

    private CCDDisplayOrderClass plainComplex;
  }

  ComplexTypeGenerator<CCDDisplayOrderClass, State, UserRole> complexTypeGenerator;

  @Test
  public void readsTheFieldTypeFromAClassValuedTypeParameterClass() {
    // typeParameterClass already makes the named class part of the definition (complex-type resolution
    // walks it exactly as a declared field type, so it emits its ComplexTypes rows). This is the other
    // half: the field must also be TYPED as it, or the definition declares a complex type nothing
    // references while the column names the DECLARED class's own ID instead.
    List<Map<String, Object>> generated = CaseFieldGenerator
        .toComplex(CCDNamedComplexTypeClass.class, "CCDNamedComplexTypeClass");

    assertThat(row(generated, "name")).containsEntry("FieldType", "jointPartyName");
    // Collection: the named class is the element type, so it lands on the FieldTypeParameter.
    assertThat(row(generated, "epimsIds"))
        .containsEntry("FieldType", "Collection")
        .containsEntry("FieldTypeParameter", "hearingVenueEpimsId");
    // typeOverride wins — populateFieldMetadata returns before type resolution runs at all.
    assertThat(row(generated, "overridden")).containsEntry("FieldType", "Text");
    // And an un-annotated complex field keeps its declared class's own name.
    assertThat(row(generated, "plainComplex"))
        .containsEntry("FieldType", "CCDDisplayOrderClass");
  }

  @Test
  public void writesDisplayContextParameterOntoTheMemberRow() {
    // A complex-type member has no builder — ComplexTypeGenerator derives every row from
    // CaseFieldGenerator.toComplex — so @CCD is the only place a member's display directive can
    // live. The importer reads the column on the ComplexTypes sheet (ComplexFieldTypeParser), which
    // is how a hand-written definition's date format on e.g. appellant.confidentialityRequired-
    // ConfirmedDate is expressible in Java at all.
    List<Map<String, Object>> generated = CaseFieldGenerator
        .toComplex(CCDDisplayContextParameterClass.class, "CCDDisplayContextParameterClass");

    assertThat(row(generated, "confirmedDate"))
        .containsEntry("DisplayContextParameter", "#DATETIMEDISPLAY(d MMM yyyy, h:mm:ss a)");
    // Absent by default: no column at all rather than an empty one, so a member that does not set
    // it still compares equal to a definition row that has no such column.
    assertThat(row(generated, "plain")).doesNotContainKey("DisplayContextParameter");
  }

  private static Map<String, Object> row(List<Map<String, Object>> rows, String id) {
    return rows.stream()
        .filter(r -> id.equals(r.get("ID")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no generated row for " + id));
  }

  @Test
  public void shouldSortClassFieldsByDisplayOrder() {

    List<Map<String, Object>> generatedFromCCDTestClass = CaseFieldGenerator
      .toComplex(CCDDisplayOrderClass.class, "CCDDisplayOrderClass");

    complexTypeGenerator = new ComplexTypeGenerator<>();
    complexTypeGenerator.sortComplexTypesByDisplayOrder(generatedFromCCDTestClass);

    assertThat(generatedFromCCDTestClass.get(0).get("DisplayOrder")).isEqualTo(1);
    assertThat(generatedFromCCDTestClass.get(5).get("DisplayOrder")).isEqualTo(6);
    assertThat(generatedFromCCDTestClass.get(6).get("DisplayOrder")).isNull();
  }

  @Test
  public void sortShouldNotChangeFieldOrderIfNoDisplayOrderAttribute() {

    List<Map<String, Object>> generatedFromCCDTestClass = CaseFieldGenerator
      .toComplex(CCDNoDisplayOrderClass.class, "CCDNoDisplayOrderClass");

    List<String> expected = new ArrayList<>();
    for(Map<String, Object> fieldMap : generatedFromCCDTestClass){
      expected.add((String) fieldMap.get("Label"));
    }
    complexTypeGenerator = new ComplexTypeGenerator<>();
    complexTypeGenerator.sortComplexTypesByDisplayOrder(generatedFromCCDTestClass);

    int counter = 0;
    for(Map<String, Object> fieldMap : generatedFromCCDTestClass){
      assertThat(generatedFromCCDTestClass.get(counter).get("Label")).isEqualTo(expected.get(counter));
      counter++;
    }
  }
}
