package uk.gov.hmcts.ccd.sdk;

import static junit.framework.TestCase.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.groovy.util.Maps;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FunctionalTest {

  @Rule
  public final TemporaryFolder testProjectDir = new TemporaryFolder();
  private File buildFile;

  @Before
  public void setup() throws IOException {
    buildFile = testProjectDir.newFile("build.gradle");
  }

  @Test
  public void testEmptyProject() throws IOException {
    String buildFileContent = "plugins {"
        + "    id 'java' \n"
        + "    id 'hmcts.ccd.sdk'"
        + "}\n"
        + "ccd {"
        + "configDir file('ccd-definition')\n"
        + "}";
    Files.write(buildFile.toPath(), buildFileContent.getBytes());

    BuildResult result = runner(testProjectDir.getRoot())
        .withGradleVersion("4.10.3")
        .buildAndFail();

    System.out.println(result.getOutput());
    assertTrue(result.getOutput().contains("Expected at least one"));
    assertTrue(result.getOutput().contains("Searched package: uk.gov.hmcts"));
  }

  public static GradleRunner runner(File project) {
    return GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withProjectDir(project)
        .withArguments("generateCCDConfig", "-si")
        .withEnvironment(Maps.of("GRADLE_FUNCTIONAL_TEST", "true"));
  }
}

