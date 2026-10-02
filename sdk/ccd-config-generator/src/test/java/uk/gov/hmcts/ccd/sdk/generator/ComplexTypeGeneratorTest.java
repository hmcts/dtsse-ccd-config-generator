package uk.gov.hmcts.ccd.sdk.generator;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.Ignore;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.Document;
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

  @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
  public static class CCDJsonNamingClass {
    @CCD(label = "Name")
    private String name;

    @CCD(label = "Document")
    private Document document;

    @CCD(label = "Address")
    private AddressUK addressUk;

    @CCD(label = "Explicitly named")
    @JsonProperty("explicit_name")
    private String explicitName;

    public String getName() {
      return name;
    }

    public Document getDocument() {
      return document;
    }

    public AddressUK getAddressUk() {
      return addressUk;
    }

    public String getExplicitName() {
      return explicitName;
    }
  }

  ComplexTypeGenerator<CCDDisplayOrderClass, State, UserRole> complexTypeGenerator;

  @Test
  public void complexTypeFieldIdsMatchTheJsonNamesJacksonUses() {
    List<Map<String, Object>> generated = CaseFieldGenerator
      .toComplex(CCDJsonNamingClass.class, "CCDJsonNamingClass");

    List<String> generatedIds = generated.stream()
      .map(field -> (String) field.get("ID"))
      .toList();

    ObjectMapper mapper = new ObjectMapper();
    BeanDescription description = mapper.getSerializationConfig()
      .introspect(mapper.constructType(CCDJsonNamingClass.class));
    List<String> wireNames = description.findProperties().stream()
      .map(BeanPropertyDefinition::getName)
      .toList();

    // CCD only keeps complex type values whose names match these IDs, so any mismatch is data
    // that CCD silently drops.
    assertThat(generatedIds).containsExactlyInAnyOrderElementsOf(wireNames);
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
