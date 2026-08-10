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
 * Golden test for {@code @CCD(typeParameterClass)} (see
 * {@link uk.gov.hmcts.reform.TypeParamReachCaseType}). A list named by a live field emits its rows
 * under the ID its {@code @ComplexType(name)} carries; a list named only from an ignored field, and a
 * list named from nowhere, emit nothing.
 *
 * <p>The field keeps its declared {@code String} type throughout — that is the point, since retyping
 * it would change every caller and serialised payload in the team's model.
 */
@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class TypeParamReachGenerationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Autowired
    private CCDDefinitionGenerator generator;

    @Test
    @SneakyThrows
    public void emitsAListReachedOnlyThroughTypeParameterClass() {
        File out = tmp.newFolder();
        generator.generateAllCaseTypesToJSON(out);
        File caseType = new File(out, "TypeParamReach");

        Map<String, File> actual = CcdConfigComparator.dirToMap(caseType);
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap("TypeParamReach");
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE);

        // Stated directly as well as via the snapshot, so the intent survives a snapshot refresh: the
        // named list is emitted under the definition's ID, while the list named only from an ignored
        // field and the list named from nowhere are both absent.
        assertThat(new File(caseType, "FixedLists").list())
            .contains("FL_typeParamReachVenues.json")
            .doesNotContain(
                "TypeParamReachVenue.json",
                "FL_typeParamReachUnreached.json",
                "TypeParamReachUnreached.json");
    }
}
