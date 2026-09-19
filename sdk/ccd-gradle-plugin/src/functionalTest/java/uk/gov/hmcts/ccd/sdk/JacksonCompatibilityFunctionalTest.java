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
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        repositories { mavenLocal(); mavenCentral() }
        """);
    write("lombok.config", "config.stopBubbling = true\n");
  }

  @Test
  public void sourceGuardRejectsJacksonThreeAcrossSourceSetsWithoutResolvingDependencies() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        sourceSets {
            test.java.srcDir 'src/test/kotlin'
            integrationTest.java.srcDir 'src/integrationTest/groovy'
            contractTest.java.srcDir 'contracts'
        }
        """);
    write("src/main/java/Model.java", """
        // tools.jackson.databind.JsonNode is forbidden.
        import tools.jackson.databind.annotation.JsonNaming;
        @JsonNaming(tools.jackson.databind.PropertyNamingStrategies.UpperCamelCaseStrategy.class)
        class Model {}
        """);
    write("src/test/kotlin/ModelTest.kt", "import tools.jackson.databind.ObjectMapper as Mapper\n");
    write("src/integrationTest/groovy/Mapper.groovy", "import tools.jackson.core.*\n");
    write("contracts/Model.java", "class Model { tools.jackson.databind.JsonNode node; }\n");
    write("build/generated/Old.java", "import tools.jackson.databind.ObjectMapper;\n");
    write("node_modules/package/Old.java", "import tools.jackson.databind.ObjectMapper;\n");

    BuildResult result = runner("jackson2CompatibilityGuard").buildAndFail();
    assertEquals(TaskOutcome.FAILED, result.task(":jackson2CompatibilityGuard").getOutcome());
    String report = report();
    assertTrue(report.contains("src/main/java/Model.java:2 [JACKSON3_API]"));
    assertFalse(report.contains("src/main/java/Model.java:1"));
    assertTrue(report.contains("src/test/kotlin/ModelTest.kt:1"));
    assertTrue(report.contains("src/integrationTest/groovy/Mapper.groovy:1"));
    assertTrue(report.contains("contracts/Model.java:1"));
    assertFalse(report.contains("build/generated/Old.java"));
    assertFalse(report.contains("node_modules/package/Old.java"));
    assertFalse(result.getOutput().contains("Could not resolve"));
  }

  @Test
  public void sourceGuardAllowsJacksonTwoAndRejectsJacksonThreeConfiguration() throws IOException {
    write("src/main/java/Model.java", """
        import com.fasterxml.jackson.databind.annotation.JsonNaming;
        import com.fasterxml.jackson.databind.ObjectMapper;
        @JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
        class Model { ObjectMapper mapper; }
        """);
    write("src/main/resources/application.yaml", """
        spring:
          jackson2:
            serialization:
              indent-output: true
          http:
            converters:
              preferred-json-mapper: jackson2
        """);
    runner("jackson2CompatibilityGuard").build();
    assertTrue(report().contains("0 findings"));

    write("src/main/resources/application.yaml", """
        spring:
          jackson:
            serialization:
              indent-output: true
          http:
            converters:
              preferred-json-mapper: jackson3
        """);
    runner("jackson2CompatibilityGuard").buildAndFail();
    assertTrue(report().contains("[JACKSON3_CONFIG]"));
  }

  @Test
  public void sourceGuardRejectsJacksonizedConfiguredForJacksonThree() throws IOException {
    write("src/main/java/Model.java", "@lombok.extern.jackson.Jacksonized class Model {}\n");
    runner("jackson2CompatibilityGuard").build();
    write("lombok.config", """
        config.stopBubbling = true
        lombok.jacksonized.jacksonVersion += 3
        """);
    runner("jackson2CompatibilityGuard").buildAndFail();
    assertTrue(report().contains("[JACKSONIZED_CONFIG]"));
  }

  @Test
  public void reportOnlyDoesNotAllowSubsequentCheckToPass() throws IOException {
    write("src/main/java/Old.java", "import tools.jackson.databind.ObjectMapper;\n");
    BuildResult inventory = runner("jackson2CompatibilityGuard", "--report-only").build();
    assertEquals(TaskOutcome.SUCCESS, inventory.task(":jackson2CompatibilityGuard").getOutcome());
    assertTrue(report().contains("1 findings"));
    assertEquals(TaskOutcome.FAILED,
        runner("check", "-x", "test").buildAndFail().task(":jackson2CompatibilityGuard").getOutcome());
  }

  @Test
  public void classpathGuardRejectsProjectJacksonThreeAnnotationsAndApiReferences() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        repositories { mavenLocal(); mavenCentral() }
        dependencies { compileOnly 'tools.jackson.core:jackson-databind:3.2.0' }
        """);
    write("src/main/java/Request.java", """
        @tools.jackson.databind.annotation.JsonNaming(
            tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
        class Request { tools.jackson.databind.JsonNode value; }
        """);

    BuildResult result = runner("jackson2ClasspathGuard").buildAndFail();
    assertEquals(TaskOutcome.FAILED, result.task(":jackson2ClasspathGuard").getOutcome());
    String report = classpathReport();
    assertTrue(report.contains("ERROR [PROJECT_OUTPUT] project(:)"));
    assertTrue(report.contains("JACKSON3_DATABIND_ANNOTATION"));
    assertTrue(report.contains("tools.jackson.databind.annotation.JsonNaming"));
    assertTrue(report.contains("JACKSON3_API_REFERENCE"));
    assertTrue(report.contains("tools.jackson.databind.JsonNode"));
  }

  @Test
  public void classpathGuardReportsButAllowsUnusedThirdPartyJacksonThreeArtifact() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        repositories { mavenLocal(); mavenCentral() }
        configurations.configureEach {
            exclude group: 'com.github.hmcts', module: 'ccd-config-generator'
        }
        dependencies { implementation 'tools.jackson.core:jackson-databind:3.2.0' }
        """);
    write("src/main/java/Plain.java", "class Plain {}\n");

    runner("jackson2ClasspathGuard").build();
    String report = classpathReport();
    assertTrue(report.contains("JACKSON3_ARTIFACT"));
    assertTrue(report.contains("tools.jackson.core:jackson-databind:"));
    assertTrue(report.contains("INFO [THIRD_PARTY_DEPENDENCY]"));
  }

  @Test
  public void classpathGuardReportsJacksonThreeInProjectDependencyWithoutLoadingClasses() throws IOException {
    write("settings.gradle", "rootProject.name = 'compatibility-test'\ninclude 'shared'\n");
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        repositories { mavenLocal(); mavenCentral() }
        dependencies { implementation project(':shared') }
        """);
    write("shared/build.gradle", """
        plugins { id 'java-library' }
        repositories { mavenLocal(); mavenCentral() }
        dependencies { compileOnly 'tools.jackson.core:jackson-databind:3.2.0' }
        """);
    write("shared/src/main/java/shared/Detached.java", """
        package shared;
        public class Detached { tools.jackson.databind.JsonNode value; }
        """);

    runner("jackson2ClasspathGuard").build();
    String report = classpathReport();
    assertTrue(report.contains("INFO [FIRST_PARTY_DEPENDENCY] project(:shared)"));
    assertTrue(report.contains("shared.Detached"));
    assertTrue(report.contains("tools.jackson.databind.ValueDeserializer"));
  }

  @Test
  public void classpathGuardAllowsJacksonTwoArtifactsAndBytecode() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        repositories { mavenLocal(); mavenCentral() }
        configurations.configureEach {
            exclude group: 'com.github.hmcts', module: 'ccd-config-generator'
        }
        dependencies { implementation 'com.fasterxml.jackson.core:jackson-databind:2.22.1' }
        """);
    write("src/main/java/Allowed.java", """
        @com.fasterxml.jackson.databind.annotation.JsonNaming(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
        class Allowed { com.fasterxml.jackson.databind.JsonNode value; }
        """);

    assertEquals(TaskOutcome.SUCCESS,
        runner("jackson2ClasspathGuard").build().task(":jackson2ClasspathGuard").getOutcome());
    assertTrue(classpathReport().contains("Enforced Jackson 3 findings: 0"));
  }

  @Test
  public void classpathGuardFailsExplicitlyForMalformedArtifacts() throws IOException {
    write("build.gradle", """
        plugins { id 'hmcts.ccd.sdk' }
        repositories { mavenLocal(); mavenCentral() }
        dependencies { implementation files('broken.jar') }
        """);
    write("broken.jar", "not a jar");

    BuildResult result = runner("jackson2ClasspathGuard").buildAndFail();
    assertTrue(result.getOutput().contains("Cannot read classpath artifact"));
    assertTrue(classpathReport().contains("SCAN FAILURE"));
    assertTrue(classpathReport().contains("broken.jar"));
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

  private String classpathReport() throws IOException {
    return Files.readString(
        directory.getRoot().toPath().resolve("build/reports/jackson-compatibility/classpath-report.txt"));
  }
}
