package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;

public class FPLConfigGenerationTests {
    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder();

    private static Path prodConfig;

    static ConfigGenerator generator;
    static Reflections reflections;

    @BeforeClass
    public static void before() throws IOException, URISyntaxException {
        prodConfig = tmp.getRoot().toPath().resolve("production");
        Path resRoot = Paths.get(Resources.getResource("ccd-definition").toURI());
        FileUtils.copyDirectory(resRoot.resolve("ComplexTypes").toFile(), prodConfig.resolve("ComplexTypes").toFile());
//        FileUtils.copyDirectory(resRoot.resolve("AuthorisationCaseField").toFile(), prodConfig.resolve("AuthorisationCaseField").toFile());
        FileUtils.copyDirectory(resRoot.resolve("FixedLists").toFile(), prodConfig.resolve("FixedLists").toFile());

        copyResourceToOutput("FixedLists/ProceedingType.json");
        copyResourceToOutput("FixedLists/OrderStatus.json");
        copyResourceToOutput("FixedLists/DirectionAssignee.json");

        reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage("uk.gov.hmcts"))
                .setExpandSuperTypes(false));

        generator = new ConfigGenerator(reflections, "uk.gov.hmcts");
        generator.resolveConfig(tmp.getRoot());
        // Generate a second time to ensure existing config is correctly merged.
        generator.resolveConfig(tmp.getRoot());
    }

    @SneakyThrows
    static void copyResourceToOutput(String path) {
        Path resRoot = Paths.get(Resources.getResource("ccd-definition").toURI());
        File dest = prodConfig.resolve(path).toFile();
        Files.createParentDirs(dest);
        FileUtils.copyFile(resRoot.resolve(path).toFile(), dest);
    }

    @Ignore
    @Test
    public void generatesAuthorisationCaseState() {
        assertEquals("AuthorisationCaseState.json");
    }

    @Ignore
    @Test
    public void generatesAuthorisationCaseFieldSystem() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-systemupdate.json");
    }

    @Ignore
    @Test
    public void generatesAuthorisationCaseFieldJudiciary() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-judiciary.json");
    }

    @Ignore
    @Test
    public void generatesAuthorisationCaseFieldCafcass() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-cafcass.json");
    }

    @Ignore
    @Test
    public void generatesAuthorisationSolicitor() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-solicitor.json");
    }


    @Ignore
    @Test
    public void generatesCaseTypeTab() {
        assertEquals("CaseTypeTab.json");
    }

    @Ignore
    @Test
    public void generatesWorkBasketResultFields() {
        assertEquals("WorkBasketResultFields.json");
    }

    @Ignore
    @Test
    public void generatesWorkBasketInputFields() {
        assertEquals("WorkBasketInputFields.json");
    }

    @Test
    public void generatesAllComplexTypes() {
        assertResourceFolderMatchesGenerated("ComplexTypes");
        assertGeneratedFolderMatchesResource("ComplexTypes");
    }

    @Ignore
    @Test
    public void generatesAllCaseEventToField() {
        assertResourceFolderMatchesGenerated("CaseEventToFields");
    }

    @Ignore
    @Test
    public void generatesAllCaseEvent() {
        assertResourceFolderMatchesGenerated("CaseEvent");
    }

    @Ignore
    @Test
    public void generatesAllCaseEventToComplexTypes() {
        assertResourceFolderMatchesGenerated("CaseEventToComplexTypes");
    }

    @Ignore
    @Test
    public void generatesCaseField() {
        assertEquals("CaseField.json");
    }

    @Ignore
    @Test
    public void generatesAuthorisationCaseEvent() {
        assertEquals("AuthorisationCaseEvent/AuthorisationCaseEvent.json");
    }

    @Test
    public void generatesFixedLists() {
        assertGeneratedFolderMatchesResource("FixedLists");
    }

    private void assertGeneratedFolderMatchesResource(String folder) {
        URL u = Resources.getResource("ccd-definition/" + folder);
        File resourceDir = new File(u.getPath());
        File dir = prodConfig.resolve(folder).toFile();
        for (Iterator<File> it = FileUtils.iterateFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
            File expected = it.next();
            Path path = dir.toPath().relativize(expected.toPath());
            Path actual = resourceDir.toPath().resolve(path);
            assertEquals(expected, actual.toFile());
        }
    }

    private void assertResourceFolderMatchesGenerated(String folder) {
        URL u = Resources.getResource("ccd-definition/" + folder);
        File dir = new File(u.getPath());
        assert dir.exists();
        for (Iterator<File> it = FileUtils.iterateFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
            File expected = it.next();
            Path path = dir.toPath().relativize(expected.toPath());
            Path actual = prodConfig.resolve(folder).resolve(path);
            assertEquals(expected, actual.toFile());
        }
    }

    private void assertEquals(String jsonPath) {
        System.out.println("Comparing " + jsonPath);
        URL u = Resources.getResource("ccd-definition/" + jsonPath);
        File expected = new File(u.getPath());
        File actual = new File(prodConfig.toFile(), jsonPath);
        assertEquals(expected, actual);
    }

    @SneakyThrows
    private void assertEquals(File expected, File actual) {
        try {
            String expectedString = FileUtils.readFileToString(expected, Charset.defaultCharset());
            String actualString = FileUtils.readFileToString(actual, Charset.defaultCharset());
            JSONCompareResult result = JSONCompare.compareJSON(expectedString, actualString, JSONCompareMode.LENIENT);
            if (result.failed()) {
                System.out.println("Failed comparing " + expected.getName() + " to " + actual.getName());
                System.out.println(result.toString());

                Collection<Map<String, Object>> missing = Collections2
                    .filter(fromJSON(expectedString), Predicates.not(Predicates.in(fromJSON(actualString))));
                Collection<Map<String, Object>> unexpected = Collections2
                    .filter(fromJSON(actualString), Predicates.not(Predicates.in(fromJSON(expectedString))));

                System.out.println(missing.size() + " missing values:");
                System.out.println(pretty(missing));

                System.out.println(unexpected.size() + " unexpected values:");
                System.out.println(pretty(unexpected));

//                System.out.println("Expected:");
//                System.out.println(expectedString);
//                System.out.println("Got:");
//                System.out.println(actualString);

//                Files.writeString(new File("src/test/resources/ccd-definition/mine.json").toPath(), actualString);
//                Files.writeString(new File("src/test/resources/ccd-definition/errors.json").toPath(), result.toString());

                throw new RuntimeException("Compare failed for " + expected.getName());
            }

        } catch (Exception e) {
            System.out.println("Generated files:");
            for (Iterator<File> it = FileUtils.iterateFiles(prodConfig.toFile(), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
                File f = it.next();
                System.out.println(f.getPath());
            }
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    private List<Map<String, Object>> fromJSON(String json) {
        ObjectMapper mapper = new ObjectMapper();
        CollectionType mapCollectionType = mapper.getTypeFactory()
            .constructCollectionType(List.class, Map.class);
        return mapper.readValue(json, mapCollectionType);
    }

    @SneakyThrows
    private String pretty(Object obj) {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }

    @SneakyThrows
    private String pretty(String json) {
        ObjectMapper mapper = new ObjectMapper();
        Object o = mapper.readValue(json, Object.class);
        return pretty(o);
    }
}
