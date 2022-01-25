package uk.gov.hmcts.ccd.sdk.generator;

import org.junit.Ignore;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCD;
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

  ComplexTypeGenerator<CCDDisplayOrderClass, State, UserRole> complexTypeGenerator;

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
