package uk.gov.hmcts.ccd.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real Gradle builds test the plugin lifecycle without requiring a CCD stack. */
public class JacksonCompatibilityFunctionalTest {
  @Rule
  public final TemporaryFolder directory = new TemporaryFolder();

  @Before
  public void setup() throws IOException {
    write("settings.gradle", "rootProject.name = 'compatibility-test'\n");
    write("build.gradle", "plugins { id 'hmcts.ccd.sdk' }\n");
    write("lombok.config", "config.stopBubbling = true\n");
  }

  @Test
  public void checkAutomaticallyRejectsLegacyApisAcrossSourceSetsWithoutCompiling() throws IOException {
    write("src/main/java/Model.java", """
        // com.fasterxml.jackson.databind.annotation.JsonNaming is obsolete.
        import com.fasterxml.jackson.databind.annotation.JsonNaming;
        @JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.UpperCamelCaseStrategy.class)
        class Model {}
        """);
    write("src/test/kotlin/ModelTest.kt", "import com.fasterxml.jackson.databind.ObjectMapper as LegacyMapper\n");
    write("src/integrationTest/groovy/Mapper.groovy", "import com.fasterxml.jackson.core.*\n");
    var result = runner("check", "-x", "test").buildAndFail();
    assertEquals(TaskOutcome.FAILED, result.task(":checkJacksonCompatibility").getOutcome());
    String report = report();
    assertTrue(report.contains("src/main/java/Model.java:2 [JACKSON2_API]"));
    assertFalse(report.contains("src/main/java/Model.java:1"));
    assertTrue(report.contains("src/test/kotlin/ModelTest.kt:1"));
    assertTrue(report.contains("src/integrationTest/groovy/Mapper.groovy:1"));
    assertFalse(result.getOutput().contains("Could not resolve"));
  }

  @Test
  public void sharedAnnotationsAndJacksonThreePassAndReportsInvalidateOnChange() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        dependencies { implementation 'com.fasterxml.jackson.core:jackson-annotations:2.21' }
        """);
    write("src/main/java/Model.java", """
        import com.fasterxml.jackson.annotation.JsonProperty;
        import tools.jackson.databind.annotation.JsonNaming;
        // import com.fasterxml.jackson.databind.ObjectMapper;
        /* import com.fasterxml.jackson.core.JsonParser; */
        @JsonNaming(tools.jackson.databind.PropertyNamingStrategies.UpperCamelCaseStrategy.class)
        class Model { @JsonProperty("Name") String name; }
        """);
    write("src/main/resources/application.yaml", """
        # preferred-json-mapper: jackson2
        spring.jackson.use-jackson2-defaults: true
        """);
    assertEquals(TaskOutcome.SUCCESS, runner("check", "-x", "test").build().task(":check").getOutcome());
    assertTrue(report().contains("0 findings"));
    assertEquals(TaskOutcome.UP_TO_DATE,
        runner("checkJacksonCompatibility").build().task(":checkJacksonCompatibility").getOutcome());
    write("src/main/java/Model.java", "class Model { com.fasterxml.jackson.databind.ObjectMapper mapper; }\n");
    assertEquals(TaskOutcome.FAILED,
        runner("checkJacksonCompatibility").buildAndFail().task(":checkJacksonCompatibility").getOutcome());
  }

  @Test
  public void detectsDependenciesEvenWhenBuildUsesVariablesAndCatalogs() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        def legacyGroup = 'com.fasterxml.jackson.core'
        def legacyModule = 'jackson-databind'
        dependencies {
            implementation group: legacyGroup, name: legacyModule, version: '2.19.4'
            testImplementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.4'
            implementation 'org.springframework.boot:spring-boot-jackson2:4.0.8'
        }
        """);
    write("gradle/libs.versions.toml", """
        [libraries]
        legacy-json = { module = "com.fasterxml.jackson.module:jackson-module-parameter-names", version = "2.19.4" }
        annotations = { module = "com.fasterxml.jackson.core:jackson-annotations", version = "2.21" }
        """);
    runner("checkJacksonCompatibility").buildAndFail();
    assertTrue(report().contains("[JACKSON2_DEPENDENCY] implementation: com.fasterxml.jackson.core:jackson-databind"));
    assertTrue(report().contains("[JACKSON2_DEPENDENCY] testImplementation: com.fasterxml.jackson.datatype:"));
    assertTrue(report().contains("gradle/libs.versions.toml:2 [JACKSON2_COORDINATE]"));
    assertFalse(report().contains("gradle/libs.versions.toml:3"));
  }

  @Test
  public void detectsMvcSelectionAndLegacySpringIntegrationButIgnoresComments() throws IOException {
    write("src/main/resources/application.yaml", """
        spring:
          http:
            converters:
              preferred-json-mapper: 'jackson2'
        """);
    write("config/application-prod.properties", "spring.http.converters.preferred-json-mapper=jackson2\n");
    write("config/deployment.yaml", "SPRING_HTTP_CONVERTERS_PREFERRED_JSON_MAPPER: jackson2\n");
    write("src/main/java/JsonConfig.java", """
        import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
        import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
        // MappingJackson2HttpMessageConverter
        class JsonConfig {}
        """);
    runner("checkJacksonCompatibility").buildAndFail();
    assertTrue(report().contains("src/main/resources/application.yaml:4 [JACKSON2_MVC]"));
    assertTrue(report().contains("config/application-prod.properties:1 [JACKSON2_MVC]"));
    assertTrue(report().contains("config/deployment.yaml:1 [JACKSON2_MVC]"));
    assertTrue(report().contains("src/main/java/JsonConfig.java:1 [JACKSON2_SPRING]"));
    assertFalse(report().contains("src/main/java/JsonConfig.java:3"));
  }

  @Test
  public void detectsJacksonizedDefaultAndNestedOverride() throws IOException {
    write("src/main/java/Model.java", "@lombok.extern.jackson.Jacksonized class Model {}\n");
    runner("checkJacksonCompatibility").buildAndFail();
    assertTrue(report().contains("[JACKSONIZED_CONFIG]"));
    write("lombok.config", "config.stopBubbling = true\nlombok.jacksonized.jacksonVersion += 3\n");
    runner("checkJacksonCompatibility").build();
    write("src/main/java/nested/lombok.config", """
        clear lombok.jacksonized.jacksonVersion
        lombok.jacksonized.jacksonVersion += 2
        """);
    write("src/main/java/nested/Other.java", "@Jacksonized class Other {}\n");
    runner("checkJacksonCompatibility").buildAndFail();
    assertTrue(report().contains("src/main/java/nested/Other.java:1 [JACKSONIZED_CONFIG]"));
    assertFalse(report().contains("src/main/java/Model.java:1"));
  }

  @Test
  public void scansCustomSourcesAndBuildScriptsButSkipsBuildAndDependencyCaches() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        layout.buildDirectory = file('generated-output')
        sourceSets { contractTest { java.srcDir 'contracts' } }
        apply from: 'gradle/legacy.gradle'
        """);
    write("contracts/Legacy.java", "class Legacy { com.fasterxml.jackson.databind.ObjectMapper mapper; }\n");
    write("gradle/legacy.gradle", "// obsolete com.fasterxml.jackson.databind.ObjectMapper\n");
    write("generated-output/Old.java", "import com.fasterxml.jackson.databind.ObjectMapper;\n");
    write("node_modules/package/Old.java", "import com.fasterxml.jackson.databind.ObjectMapper;\n");
    write(".gradle/Old.java", "import com.fasterxml.jackson.databind.ObjectMapper;\n");
    runner("checkJacksonCompatibility").buildAndFail();
    String result = Files.readString(directory.getRoot().toPath()
        .resolve("generated-output/reports/jackson-compatibility/report.txt"));
    assertTrue(result.contains("1 findings"));
    assertTrue(result.contains("contracts/Legacy.java:1"));
  }

  @Test
  public void handlesLargeQuotedFixturesAndKeepsReflectiveClassNames() throws IOException {
    write("src/test/resources/large.json", "{\"message\":\"" + "x".repeat(200_000)
        + "\",\"escaped\":\"" + "\\\"".repeat(20_000) + "\"}");
    write("src/main/java/Reflection.java", """
        class Reflection {
            String type = "com.fasterxml.jackson.databind.ObjectMapper";
        }
        """);
    runner("checkJacksonCompatibility").buildAndFail();
    assertTrue(report().contains("1 findings"));
    assertTrue(report().contains("src/main/java/Reflection.java:2 [JACKSON2_API]"));
  }

  @Test
  public void reportOnlyDoesNotAllowSubsequentCheckToPass() throws IOException {
    write("src/main/java/Old.java", "import com.fasterxml.jackson.databind.ObjectMapper;\n");
    BuildResult inventory = runner("checkJacksonCompatibility", "--report-only").build();
    assertEquals(TaskOutcome.SUCCESS, inventory.task(":checkJacksonCompatibility").getOutcome());
    assertTrue(report().contains("1 findings"));
    assertEquals(TaskOutcome.FAILED,
        runner("check", "-x", "test").buildAndFail().task(":checkJacksonCompatibility").getOutcome());
  }

  private GradleRunner runner(String... arguments) {
    return GradleRunner.create().withPluginClasspath().withProjectDir(directory.getRoot()).withArguments(arguments);
  }

  private void write(String name, String content) throws IOException {
    Path path = directory.getRoot().toPath().resolve(name);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  private String report() throws IOException {
    return Files.readString(directory.getRoot().toPath().resolve("build/reports/jackson-compatibility/report.txt"));
  }
}
