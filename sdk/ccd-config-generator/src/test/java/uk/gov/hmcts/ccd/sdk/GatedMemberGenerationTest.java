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
 * Golden test for {@code @CCD(gate = ...)} on a COMPLEX-TYPE MEMBER (see
 * {@link uk.gov.hmcts.reform.GatedMemberCaseType}). The same case type is generated twice
 * in-process — once with {@code CCD_DEF_JO} set so the gate matches, once without so it does not —
 * and each shape is snapshotted, exactly as {@link GatedFieldGenerationTest} does for a CaseData
 * field.
 *
 * <p>With the gate active the gated member emits its {@code ComplexTypes} row on
 * {@code GatedMemberComplex} and the nested {@code GatedMemberNested} type is present. With it
 * inactive that member row vanishes and — because {@code GatedMemberNested} is reachable only
 * through the gated member — the whole nested type disappears from the {@code ComplexTypes}
 * directory, while the ungated {@code alwaysMember} row and every other sheet stay byte-identical.
 * This is the same choke-point philosophy as CaseField-level gating: the member is filtered at
 * {@code FieldUtils.getCaseFields}/{@code ConfigResolver.resolve} rather than per generator.
 */
@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class GatedMemberGenerationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Autowired
    private CCDDefinitionGenerator generator;

    @Test
    public void emitsGatedMemberWhenGateActive() {
        File out = generateWithGate("true");
        assertGatedDefinitionMatches(out, "GatedMemberGateOn");
    }

    @Test
    public void omitsGatedMemberWhenGateInactive() {
        File out = generateWithGate(null);
        assertGatedDefinitionMatches(out, "GatedMemberGateOff");
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
        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(out, "GatedMember"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap(expectedResource);
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }
}
