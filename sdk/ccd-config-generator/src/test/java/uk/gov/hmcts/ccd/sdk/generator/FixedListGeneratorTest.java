package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A fixed list is emitted for the lists the generated definition actually references, not for every
 * enum reflection happens to reach. An enum class becomes reachable as soon as SOME field declares
 * it, but a field is free to declare itself to be something else — {@code @CCD(typeOverride)} —
 * or to hold the enum purely as an in-Java value, and neither case produces a list in CCD.
 */
public class FixedListGeneratorTest {

    private static final String CASE_TYPE = "TEST_CASE_TYPE";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    enum Colour {
        RED, GREEN
    }

    enum Shape {
        SQUARE, ROUND
    }

    @ComplexType(name = "Nested", generate = true)
    static class Nested {
        private Shape shape;
    }

    static class CaseDataWithOverriddenEnum {
        // Declares Colour, but tells CCD it is a Text column — exactly sscs's
        // `@CCD(typeOverride = FieldType.Text) private DirectionType directionType`.
        @CCD(typeOverride = FieldType.Text)
        private Colour colour;
    }

    static class CaseDataUsingBothWays {
        @CCD(typeOverride = FieldType.Text)
        private Colour textCarrier;
        // The same enum on a second field that IS a fixed list. One live reference is enough.
        private Colour realList;
    }

    static class CaseDataWithNestedEnum {
        private Nested nested;
    }

    @Test
    public void emitsNoListForAnEnumEveryFieldOverridesToSomethingElse() {
        assertThat(listsFor(CaseDataWithOverriddenEnum.class, Colour.class)).isEmpty();
    }

    @Test
    public void emitsTheListWhenAnyFieldReferencesItAsAList() {
        assertThat(listsFor(CaseDataUsingBothWays.class, Colour.class))
            .containsExactly("Colour.json");
    }

    @Test
    public void emitsAListReferencedOnlyByAComplexTypeMember() {
        assertThat(listsFor(CaseDataWithNestedEnum.class, Nested.class, Shape.class))
            .containsExactly("Shape.json");
    }

    @SneakyThrows
    private List<String> listsFor(Class<?> caseClass, Class<?>... reachable) {
        Map<Class, Integer> types = new LinkedHashMap<>();
        for (Class<?> c : reachable) {
            types.put(c, 0);
        }
        ResolvedCCDConfig<Object, State, UserRole> resolved = new ResolvedCCDConfig<>(
            (Class<Object>) caseClass, State.class, UserRole.class, types,
            ImmutableSet.copyOf(State.values()));
        ConfigBuilderImpl<Object, State, UserRole> builder = new ConfigBuilderImpl<>(resolved);
        builder.caseType(CASE_TYPE, "Test", "Test case type");

        new FixedListGenerator<Object, State, UserRole>().write(tmp.getRoot(), builder.build());

        File dir = new File(tmp.getRoot(), "FixedLists");
        String[] files = dir.list();
        return files == null ? List.of() : List.of(files);
    }
}
