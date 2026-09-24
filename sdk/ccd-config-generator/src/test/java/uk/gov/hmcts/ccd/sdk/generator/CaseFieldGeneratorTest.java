package uk.gov.hmcts.ccd.sdk.generator;

import org.assertj.core.api.Condition;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.math.BigDecimal;
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
                // The type a service reaches for when a Number field holds money. Left out of the
                // numeric list, it did not degrade to something imprecise: the switch yields the
                // inferred type verbatim, so the field emitted FieldType=BigDecimal, which the
                // definition store's base types do not contain, and the import failed outright.
                {"decimalField", "Number"},
                {"longField", "Number"},
                {"LongField", "Number"}
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
        private BigDecimal decimalField;
        private long longField;
        private Long LongField;
    }

    @Parameterized.Parameter
    public String fieldName;

    @Parameterized.Parameter(1)
    public String expectedType;


    @Test
    public void shouldConvertStringToAppropriateTextType() {
        List<Map<String, Object>> generatedFromTestClass = CaseFieldGenerator
            .toComplex(TestClass.class, "TestClass");

        Optional<Map<String, Object>> fieldUnderTest = generatedFromTestClass.stream()
                .filter(field -> field.get("ID").equals(fieldName))
                .findAny();

        Condition<Map<String, Object>> isOfExpectedType =
                new Condition<>(field -> field.get("FieldType").equals(expectedType), "of type: " + expectedType);

        assertThat(fieldUnderTest)
                .hasValueSatisfying(isOfExpectedType);
    }

}
