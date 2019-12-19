package ccd.sdk;

import ccd.sdk.generator.ConfigGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.reflections.Reflections;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public class ConfigGenerationTests {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    ConfigGenerator generator;
    Reflections reflections;

    @Before
    public void before() {
        reflections = new Reflections("uk.gov.hmcts");
        generator = new ConfigGenerator(reflections, temp.getRoot());
        generator.generate("CARE_SUPERVISION_EPO");
    }

    @Test
    public void generatesAllComplexTypes() throws Exception {
        assertEquals("ComplexTypes/Solicitor.json");
        assertEquals("ComplexTypes/Recitals.json");
        assertEquals("ComplexTypes/RiskAndHarm.json");
//        assertResourceFolderMatchesGenerated("ComplexTypes");
    }

    @Test
    public void generatesStateOpen() {
        assertEquals("CaseEvent/Open.json");
    }

    @Test
    public void generatesAllCaseEventToField() {
        assertResourceFolderMatchesGenerated("CaseEventToFields");
    }

    @Test
    public void generatesAllCaseEvent() {
        assertResourceFolderMatchesGenerated("CaseEvent");
    }

    @Test
    public void generatesAllCaseEventToComplexTypes() {
        assertResourceFolderMatchesGenerated("CaseEventToComplexTypes");
    }

    @Test
    public void generatesCaseField() {
        assertEquals("CaseField.json");
    }

    // This will only pass once everything else is finished.
    @Test
    public void generatesAuthorisationCaseEvent() {
        assertEquals("AuthorisationCaseEvent/AuthorisationCaseEvent.json");
    }

    private void assertResourceFolderMatchesGenerated(String folder) {
        URL u = Resources.getResource("ccd-definition/" + folder);
        File dir = new File(u.getPath());
        assert dir.exists();
        for (Iterator<File> it = FileUtils.iterateFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
            File expected = it.next();
            if (expected.getName().endsWith(".ignore")) {
                continue;
            }
            Path path = dir.toPath().relativize(expected.toPath());
            Path actual = temp.getRoot().toPath().resolve(folder).resolve(path);
            assertEquals(expected, actual.toFile());
        }
    }

    private void assertEquals(String jsonPath) {
        System.out.println("Comparing " + jsonPath);
        URL u = Resources.getResource("ccd-definition/" + jsonPath);
        File expected = new File(u.getPath());
        File actual = new File(temp.getRoot(), jsonPath);
        assertEquals(expected, actual);
    }

    private void assertEquals(File expected, File actual) {
        try {
            System.out.println("Comparing " + expected.getName());
            String expectedString = FileUtils.readFileToString(expected, Charset.defaultCharset());
            String actualString = FileUtils.readFileToString(actual, Charset.defaultCharset());
            JSONCompareResult result = JSONCompare.compareJSON(expectedString, actualString, JSONCompareMode.LENIENT);
            if (result.failed()) {
                System.out.println(result.toString());

                System.out.println(pretty(actualString));
                System.out.println("Expected:");
                System.out.println(pretty(expectedString));

//                Files.writeString(new File("src/test/resources/ccd-definition/mine.json").toPath(), actualString);
//                Files.writeString(new File("src/test/resources/ccd-definition/errors.json").toPath(), result.toString());

                throw new RuntimeException("Compare failed for " + expected.getName());
            }

        } catch (Exception e) {
            System.out.println("Generated files:");
            for (Iterator<File> it = FileUtils.iterateFiles(temp.getRoot(), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
                File f = it.next();
                System.out.println(f.getPath());
            }
            throw new RuntimeException(e);
        }
    }

    private String pretty(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Object o = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
