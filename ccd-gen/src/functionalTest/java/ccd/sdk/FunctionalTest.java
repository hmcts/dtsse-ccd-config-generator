package ccd.sdk;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static junit.framework.TestCase.assertTrue;

public class FunctionalTest {
    @Rule public final TemporaryFolder testProjectDir = new TemporaryFolder();
    private File buildFile;

    @Before
    public void setup() throws IOException {
        buildFile = testProjectDir.newFile("build.gradle");
    }

    @Test
    public void testEmptyProject() throws IOException {
        String buildFileContent = "plugins {" +
                "    id 'java' \n" +
                "    id 'ccd.sdk'" +
                "}";
        Files.writeString(buildFile.toPath(), buildFileContent);

        BuildResult result = GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(testProjectDir.getRoot())
                .withArguments("generateCCDConfig", "-si")
                .buildAndFail();

        System.out.println(result.getOutput());
        assertTrue(result.getOutput().contains("Expected 1"));
    }
}
