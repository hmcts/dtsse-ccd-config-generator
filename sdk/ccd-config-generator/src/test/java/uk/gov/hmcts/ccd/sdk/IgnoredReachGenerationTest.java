package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.ccd.sdk.diff.CcdConfigComparator;

/**
 * Golden test for complex-type reachability through ignored fields (see
 * {@link uk.gov.hmcts.reform.IgnoredReachCaseType}). A complex type reached ONLY through
 * {@code @CCD(ignore = true)}/{@code @JsonIgnore} fields emits no {@code ComplexTypes} rows, because
 * no field in the generated definition references it; one still reached by a live field is
 * unaffected.
 *
 * <p>This is the same choke-point philosophy as {@code @CCD(gate)} (see
 * {@link GatedMemberGenerationTest}): the field is filtered once in
 * {@code ConfigResolver.resolve}'s field predicate rather than per generator.
 */
@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class IgnoredReachGenerationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Autowired
    private CCDDefinitionGenerator generator;

    @Test
    @SneakyThrows
    public void omitsAComplexTypeReachableOnlyThroughIgnoredFields() {
        File out = tmp.newFolder();
        generator.generateAllCaseTypesToJSON(out);
        File caseType = new File(out, "IgnoredReach");

        Map<String, File> actual = CcdConfigComparator.dirToMap(caseType);
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap("IgnoredReach");
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE);

        // Stated directly as well as via the snapshot, so the intent survives a snapshot refresh:
        // the ignored-only type contributes no file at all, while the shared type still does.
        assertThat(new File(caseType, "ComplexTypes").list())
            .doesNotContain("IgnoredReachNested.json")
            .contains("IgnoredReachShared.json");
    }
}
