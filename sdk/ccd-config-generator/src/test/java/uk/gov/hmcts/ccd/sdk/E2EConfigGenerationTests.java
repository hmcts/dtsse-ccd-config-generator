package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.File;
import java.net.URL;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.ccd.sdk.diff.CcdConfigComparator;

@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class E2EConfigGenerationTests {

    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder();

    @Autowired
    private CCDDefinitionGenerator generator;

    @Before
    public void before() {
        generator.generateAllCaseTypesToJSON(tmp.getRoot());
        // Generate a second time to ensure existing config is correctly merged.
        generator.generateAllCaseTypesToJSON(tmp.getRoot());
    }

    @Test
    public void generatesCareSupervisionEPO() {
        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(tmp.getRoot(), "CARE_SUPERVISION_EPO"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap("CARE_SUPERVISION_EPO");
        CcdConfigComparator.assertEquivalent(expected, actual);
    }

    @SneakyThrows
    @Test
    public void respectsComplexTypeOrdering() {
        File expected = resourceFile("CARE_SUPERVISION_EPO/ComplexTypes/HearingBooking.json");
        File actual = new File(tmp.getRoot(), "CARE_SUPERVISION_EPO/ComplexTypes/HearingBooking.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.STRICT);
    }

    @Test
    public void testCustomHistoryTabOrder() {
        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(tmp.getRoot(), "CustomHistory/CaseTypeTab"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap("CustomHistory/CaseTypeTab");
        CcdConfigComparator.assertEquivalent(expected, actual);
    }

    @Test
    public void generatesDerivedConfig() {
        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(tmp.getRoot(), "derived"));
        Map<String, File> expected = ImmutableMap.<String, File>builder()
            .putAll(CcdConfigComparator.resourcesDirToMap("CARE_SUPERVISION_EPO"))
            .putAll(CcdConfigComparator.resourcesDirToMap("derived"))
            .buildKeepingLast();
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE, "CaseTypeID");
    }

    @SneakyThrows
    private File resourceFile(String resourcePath) {
        URL url = Resources.getResource(resourcePath);
        return new File(url.toURI());
    }
}
