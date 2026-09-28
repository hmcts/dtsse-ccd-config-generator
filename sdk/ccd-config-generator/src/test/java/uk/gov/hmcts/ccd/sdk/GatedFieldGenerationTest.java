package uk.gov.hmcts.ccd.sdk;

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
 * Golden test for {@code @CCD(gate = ...)} (see {@link uk.gov.hmcts.reform.GatedFieldCaseType}).
 * The same case type is generated twice in-process — once with the {@code CCD_DEF_JO} system
 * property set so the gate matches, once without so it does not — and each shape is snapshotted.
 *
 * <p>With the gate active every row the gated field owns
 * (CaseField/AuthorisationCaseField/CaseEventToFields/CaseTypeTab/SearchInputFields) is present;
 * with it inactive they all vanish while the ungated {@code baseField} rows are byte-identical
 * across both runs. Generation reads the property fresh each time (config is re-resolved per call),
 * matching the ccd-definition-converter round-trip harness which flips environments the same way.
 */
@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class GatedFieldGenerationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Autowired
    private CCDDefinitionGenerator generator;

    @Test
    public void emitsGatedRowsWhenGateActive() {
        File out = generateWithGate("true");
        assertGatedDefinitionMatches(out, "GatedFieldGateOn");
    }

    @Test
    public void omitsGatedRowsWhenGateInactive() {
        File out = generateWithGate(null);
        assertGatedDefinitionMatches(out, "GatedFieldGateOff");
    }

    @SneakyThrows
    private File generateWithGate(String value) {
        File out = tmp.newFolder();
        if (value == null) {
            System.clearProperty("CCD_DEF_JO");
        } else {
            System.setProperty("CCD_DEF_JO", value);
        }
        try {
            generator.generateAllCaseTypesToJSON(out);
        } finally {
            System.clearProperty("CCD_DEF_JO");
        }
        return out;
    }

    @SneakyThrows
    private void assertGatedDefinitionMatches(File out, String expectedResource) {
        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(out, "GatedField"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap(expectedResource);
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }
}
