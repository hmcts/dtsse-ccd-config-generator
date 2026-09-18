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
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        sourceSets {
            test.java.srcDir 'src/test/kotlin'
            integrationTest.java.srcDir 'src/integrationTest/groovy'
            contractTest.java.srcDir 'contracts'
        }
        """);
    write("src/main/java/Model.java", """
        // com.fasterxml.jackson.databind.annotation.JsonNaming is obsolete.
        import com.fasterxml.jackson.databind.annotation.JsonNaming;
        @JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.UpperCamelCaseStrategy.class)
        class Model {}
        """);
    write("src/test/kotlin/ModelTest.kt", "import com.fasterxml.jackson.databind.ObjectMapper as LegacyMapper\n");
    write("src/integrationTest/groovy/Mapper.groovy", "import com.fasterxml.jackson.core.*\n");
    write("contracts/Legacy.java", "class Legacy { com.fasterxml.jackson.databind.JsonNode node; }\n");
    write("gradle/legacy.gradle", "ext.legacyType = 'com.fasterxml.jackson.databind.ObjectMapper'\n");
    write("build/generated/Old.java", "import com.fasterxml.jackson.databind.ObjectMapper;\n");
    write("node_modules/package/Old.java", "import com.fasterxml.jackson.databind.ObjectMapper;\n");
    write(".gradle/Old.java", "import com.fasterxml.jackson.databind.ObjectMapper;\n");
    var result = runner("check", "-x", "test").buildAndFail();
    assertEquals(TaskOutcome.FAILED, result.task(":jackson3CompatibilityGuard").getOutcome());
    String report = report();
    assertTrue(report.contains("src/main/java/Model.java:2 [JACKSON2_API]"));
    assertFalse(report.contains("src/main/java/Model.java:1"));
    assertTrue(report.contains("src/test/kotlin/ModelTest.kt:1"));
    assertTrue(report.contains("src/integrationTest/groovy/Mapper.groovy:1"));
    assertTrue(report.contains("contracts/Legacy.java:1"));
    assertFalse(report.contains("gradle/legacy.gradle"));
    assertFalse(report.contains("build/generated/Old.java"));
    assertFalse(report.contains("node_modules/package/Old.java"));
    assertFalse(report.contains(".gradle/Old.java"));
    assertTrue(result.getOutput().contains(
        "WARNING: src/main/java/Model.java:2 [JACKSON2_API]"));
    assertTrue(result.getOutput().contains(
        "WARNING: src/integrationTest/groovy/Mapper.groovy:1 [JACKSON2_API]"));
    assertFalse(result.getOutput().contains("Could not resolve"));
  }

  @Test
  public void sharedAnnotationsAndJacksonThreePassAndReportsInvalidateOnChange() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        def legacyGroup = 'com.fasterxml.jackson.core'
        def legacyModule = 'jackson-databind'
        dependencies {
            implementation 'com.fasterxml.jackson.core:jackson-annotations:2.21'
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
    write("src/test/resources/large.json", "{\"message\":\"" + "x".repeat(200_000)
        + "\",\"escaped\":\"" + "\\\"".repeat(20_000) + "\"}");
    assertEquals(TaskOutcome.SUCCESS, runner("check", "-x", "test").build().task(":check").getOutcome());
    assertTrue(report().contains("0 findings"));
    assertEquals(TaskOutcome.UP_TO_DATE,
        runner("jackson3CompatibilityGuard").build().task(":jackson3CompatibilityGuard").getOutcome());
    write("src/main/java/Model.java", """
        class Model {
            String type = "com.fasterxml.jackson.databind.ObjectMapper";
        }
        """);
    assertEquals(TaskOutcome.FAILED,
        runner("jackson3CompatibilityGuard").buildAndFail().task(":jackson3CompatibilityGuard").getOutcome());
  }

  @Test
  public void detectsMvcSelectionAndLegacySpringIntegrationButIgnoresComments() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        sourceSets.main.resources.srcDir 'config'
        """);
    write("src/main/resources/application.yaml", """
        spring:
          http:
            converters:
              preferred-json-mapper: 'jackson2'
        """);
    write("config/application-prod.properties", "spring.http.converters.preferred-json-mapper=jackson2\n");
    write("config/deployment.yaml", """
        SPRING_HTTP_CONVERTERS_PREFERRED_JSON_MAPPER: jackson2
        SPRING_JACKSON2_SERIALIZATION_INDENT_OUTPUT: true
        """);
    write("config/legacy.properties", """
        spring.jackson.read.allow-java-comments=true
        spring.jackson.write.write-dates-as-timestamps=false
        spring.jackson.parser.allow-single-quotes=true
        spring.jackson.generator.write-bigdecimal-as-plain=true
        spring.jackson.json.read.allow-java-comments=true
        """);
    write("config/application-nested.yaml", """
        spring:
          jackson:
            serialization:
              indent-output: true
            read:
              allow-java-comments: true
            json:
              write:
                write-dates-as-timestamps: false
            generator:
              write-bigdecimal-as-plain: true
          http:
            converters:
              preferred-json-mapper: jackson3
        """);
    write("src/main/java/JsonConfig.java", """
        import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
        import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
        import org.springframework.boot.jackson2.JsonMixin;
        import org.springframework.boot.jackson2.JsonObjectSerializer;
        // MappingJackson2HttpMessageConverter
        class JsonConfig {}
        """);
    runner("jackson3CompatibilityGuard").buildAndFail();
    assertTrue(report().contains("src/main/resources/application.yaml:4 [JACKSON2_CONFIG]"));
    assertTrue(report().contains("config/application-prod.properties:1 [JACKSON2_CONFIG]"));
    assertTrue(report().contains("config/deployment.yaml:1 [JACKSON2_CONFIG]"));
    assertTrue(report().contains("config/deployment.yaml:2 [JACKSON2_CONFIG]"));
    assertTrue(report().contains("config/legacy.properties:1 [JACKSON2_CONFIG]"));
    assertTrue(report().contains("config/legacy.properties:2 [JACKSON2_CONFIG]"));
    assertTrue(report().contains("config/legacy.properties:3 [JACKSON2_CONFIG]"));
    assertTrue(report().contains("config/legacy.properties:4 [JACKSON2_CONFIG]"));
    assertFalse(report().contains("config/legacy.properties:5"));
    assertTrue(report().contains("config/application-nested.yaml:5 [JACKSON2_CONFIG]"));
    assertTrue(report().contains("config/application-nested.yaml:10 [JACKSON2_CONFIG]"));
    assertFalse(report().contains("config/application-nested.yaml:3"));
    assertFalse(report().contains("config/application-nested.yaml:7"));
    assertFalse(report().contains("config/application-nested.yaml:14"));
    assertTrue(report().contains("src/main/java/JsonConfig.java:1 [JACKSON2_SPRING]"));
    assertTrue(report().contains("src/main/java/JsonConfig.java:3 [JACKSON2_SPRING]"));
    assertTrue(report().contains("src/main/java/JsonConfig.java:4 [JACKSON2_SPRING]"));
    assertFalse(report().contains("src/main/java/JsonConfig.java:5"));
  }

  @Test
  public void detectsJacksonizedDefaultAndNestedOverride() throws IOException {
    write("src/main/java/Model.java", "@lombok.extern.jackson.Jacksonized class Model {}\n");
    runner("jackson3CompatibilityGuard").buildAndFail();
    assertTrue(report().contains("[JACKSONIZED_CONFIG]"));
    write("lombok.config", "config.stopBubbling = true\nlombok.jacksonized.jacksonVersion += 3\n");
    runner("jackson3CompatibilityGuard").build();
    write("src/main/java/nested/lombok.config", """
        clear lombok.jacksonized.jacksonVersion
        lombok.jacksonized.jacksonVersion += 2
        """);
    write("src/main/java/nested/Other.java", "@Jacksonized class Other {}\n");
    runner("jackson3CompatibilityGuard").buildAndFail();
    assertTrue(report().contains("src/main/java/nested/Other.java:1 [JACKSONIZED_CONFIG]"));
    assertFalse(report().contains("src/main/java/Model.java:1"));
  }

  @Test
  public void reportOnlyDoesNotAllowSubsequentCheckToPass() throws IOException {
    write("src/main/java/Old.java", "import com.fasterxml.jackson.databind.ObjectMapper;\n");
    BuildResult inventory = runner("jackson3CompatibilityGuard", "--report-only").build();
    assertEquals(TaskOutcome.SUCCESS, inventory.task(":jackson3CompatibilityGuard").getOutcome());
    assertTrue(report().contains("1 findings"));
    assertTrue(inventory.getOutput().contains("WARNING: src/main/java/Old.java:1 [JACKSON2_API]"));
    assertEquals(TaskOutcome.FAILED,
        runner("check", "-x", "test").buildAndFail().task(":jackson3CompatibilityGuard").getOutcome());
  }

  @Test
  public void detectsLombokWithoutJacksonThreeSupportOnlyWhenJacksonizedIsUsed() throws IOException {
    write("lombok.config", "config.stopBubbling = true\nlombok.jacksonized.jacksonVersion += 3\n");
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        sourceSets { integrationTest }
        dependencies {
            compileOnly 'org.projectlombok:lombok:1.18.42'
            annotationProcessor libs.lombok
            testAnnotationProcessor 'org.projectlombok:lombok:1.18.44'
            integrationTestAnnotationProcessor group: 'org.projectlombok', name: 'lombok', version: '1.18.38'
        }
        """);
    write("gradle/libs.versions.toml", """
        [versions]
        lombok = "1.18.42"
        [libraries]
        lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
        """);
    write("src/main/java/Plain.java", "class Plain {}\n");
    runner("jackson3CompatibilityGuard").build();
    assertTrue(report().contains("0 findings"));
    write("src/main/java/Model.java", "@lombok.extern.jackson.Jacksonized @lombok.Builder class Model {}\n");
    runner("jackson3CompatibilityGuard").buildAndFail();
    String report = report();
    assertTrue(report.contains("annotationProcessor [JACKSONIZED_LOMBOK] org.projectlombok:lombok:1.18.42"));
    assertTrue(report.contains(
        "integrationTestAnnotationProcessor [JACKSONIZED_LOMBOK] org.projectlombok:lombok:1.18.38"));
    assertFalse(report.contains("testAnnotationProcessor"));
    assertFalse(report.contains("JACKSONIZED_CONFIG"));
    assertFalse(report.contains("Could not resolve"));
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
