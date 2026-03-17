package uk.gov.hmcts.ccd.sdk;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.FileUtils;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FunctionalTest {

  @Rule
  public final TemporaryFolder testProjectDir = new TemporaryFolder();

  @Before
  public void setup() throws IOException {
    FileUtils.cleanDirectory(testProjectDir.getRoot());
    FileUtils.copyDirectory(new File("test-projects/java-library"),
      testProjectDir.getRoot());
  }

  //  https://docs.gradle.org/current/userguide/compatibility.html
  @Test
  public void testGradle8MinJava21() throws IOException {
    checkTestProject("8.4");
  }

  public void checkTestProject(String gradleVersion) throws IOException {
    var r = GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("generateCCDConfig", "test", "-si")
      .withGradleVersion(gradleVersion);

    assertEquals(TaskOutcome.SUCCESS,r.build().task(":generateCCDConfig").getOutcome());
    File caseField = new File(testProjectDir.getRoot(), "build/ccd-definition/test/CaseField.json");
    assertTrue(caseField.exists());
    File tsContracts = new File(testProjectDir.getRoot(), "build/ts-bindings/test/event-contracts.ts");
    File tsDtoTypes = new File(testProjectDir.getRoot(), "build/ts-bindings/test/dto-types.ts");
    File tsClient = new File(testProjectDir.getRoot(), "build/ts-bindings/test/client.ts");
    assertTrue(tsContracts.exists());
    assertTrue(tsDtoTypes.exists());
    assertTrue(!tsClient.exists());
    String contractsContent = FileUtils.readFileToString(tsContracts, StandardCharsets.UTF_8);
    assertTrue(contractsContent.contains("\"create-widget\""));
    assertTrue(contractsContent.contains("CreateWidgetData"));
    assertTrue(contractsContent.contains("fieldNamespace: \"widget.create\""));
    assertTrue(contractsContent.contains("defineCaseBindings"));

    // Run a second time to ensure a non-clean build without changes is up to date.
    assertEquals(TaskOutcome.UP_TO_DATE,r.build().task(":generateCCDConfig").getOutcome());
  }
}
