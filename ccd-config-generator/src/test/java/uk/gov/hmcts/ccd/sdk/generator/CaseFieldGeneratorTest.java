package uk.gov.hmcts.ccd.sdk.generator;

import org.assertj.core.api.Condition;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import uk.gov.hmcts.ccd.sdk.types.CCD;
import uk.gov.hmcts.ccd.sdk.types.FieldType;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class CaseFieldGeneratorTest {

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"stringField", "Text"},
                {"intField", "Number"},
                {"IntegerField", "Number"},
                {"floatField", "Number"},
                {"FloatField", "Number"},
                {"doubleField", "Number"},
                {"DoubleField", "Number"},
                {"builderField", "Text"}
        });
    }

    private static class TestClass {
        private String stringField;

        private int intField;
        private int doubleField;
        private int floatField;
        private int FloatField;
        private int DoubleField;
        private int IntegerField;

        @CCD(type = FieldType.Text)
        private StringBuilder builderField;
    }

    @Parameterized.Parameter
    public String fieldName;

    @Parameterized.Parameter(1)
    public String expectedType;


    @Test
    public void shouldConvertSimpleFieldToAppropriateType() {
        Optional<Map<String, Object>> fieldUnderTest = getFieldUnderTest();

        Condition<Map<String, Object>> isOfExpectedType =
                new Condition<>(field -> field.get("FieldType").equals(expectedType), "of type: " + expectedType);

        assertThat(fieldUnderTest)
                .hasValueSatisfying(isOfExpectedType);
    }

    @Test
    public void shouldNotAddTypeParameterInformationToSimpleTypes() {
        Optional<Map<String, Object>> fieldUnderTest = getFieldUnderTest();

        Condition<Map<String, Object>> doesntHaveTypeInformation =
                new Condition<>(field -> !field.containsKey("FieldTypeParameter"), "of type: " + expectedType);

        assertThat(fieldUnderTest)
                .hasValueSatisfying(doesntHaveTypeInformation);
    }

    //TODO add JsonUnwrapped tests

    private Optional<Map<String, Object>> getFieldUnderTest() {
        List<Map<String, Object>> generatedFromTestClass = CaseFieldGenerator.toComplex(TestClass.class, "TestClass");

        Optional<Map<String, Object>> fieldUnderTest = generatedFromTestClass.stream()
                .filter(field -> field.get("ID").equals(fieldName))
                .findAny();
        return fieldUnderTest;
    }
}
