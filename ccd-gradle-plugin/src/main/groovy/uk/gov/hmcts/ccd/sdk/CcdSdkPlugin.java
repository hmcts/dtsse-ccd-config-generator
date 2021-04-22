package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;
import lombok.Data;
import lombok.SneakyThrows;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

public class CcdSdkPlugin implements Plugin<Project> {

  public void apply(Project project) {
    project.getPlugins().apply(JavaPlugin.class);

    // Write the zipped maven repo containing the generator to disk.
    DirectoryProperty buildDir = project.getLayout().getBuildDirectory();
    File archive = buildDir.file("generator.zip").get().getAsFile();
    // Using a lambda here break's Gradle's up-to-date checks.
    Task writeZip = project.getTasks().create("writeGenerator").doLast(new Action<Task>() {
      @Override
      @SneakyThrows
      public void execute(Task task) {
        try (InputStream is = CcdSdkPlugin.class.getClassLoader()
            .getResourceAsStream("generator/generator.zip")) {
          com.google.common.io.Files.createParentDirs(archive);
          Files.copy(is, archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    });
    writeZip.getOutputs().file(archive);

    // Extract the local maven repo.
    Copy unpackZip = project.getTasks().create("unpackGenerator", Copy.class);
    unpackZip.dependsOn(writeZip);
    unpackZip.from(project.zipTree(archive));
    Provider<Directory> generatorDir =
        buildDir.dir("generator");
    unpackZip.into(generatorDir.get().getAsFile());

    project.getTasks().getByName("compileJava").dependsOn(unpackZip);

    // Add the repo to the project's repositories.
    project.getRepositories().maven(x -> x.setUrl(generatorDir.get().getAsFile()));

    // Add the dependency on the generator which will be fetched from the local maven repo.
    project.getDependencies().add("implementation", "com.github.hmcts:ccd-config-generator:"
        + getVersion());

    // Create the task to generate CCD config.
    JavaExec generate = project.getTasks().create("generateCCDConfig", JavaExec.class);
    generate.setGroup("CCD tasks");
    generate.setMain("uk.gov.hmcts.ccd.sdk.Main");

    SourceSetContainer ssc = project.getConvention().getPlugin(JavaPluginConvention.class)
        .getSourceSets();
    SourceSet main = ssc.getByName("main");
    generate.setClasspath(main.getRuntimeClasspath());

    CCDConfig config = project.getExtensions().create("ccd", CCDConfig.class);
    config.configDir = project.getBuildDir();

    generate.doFirst(x -> generate.setArgs(Arrays.asList(
        config.configDir.getAbsolutePath(),
        config.rootPackage,
        config.caseType
    )));

    project.getRepositories().jcenter();
  }

  @SneakyThrows
  private String getVersion() {
    Properties properties = new Properties();
    properties.load(getClass().getClassLoader().getResourceAsStream("application.properties"));
    return properties.getProperty("types.version");
  }

  @Data
  static class CCDConfig {

    private File configDir;
    private String rootPackage = "uk.gov.hmcts";
    private String caseType = "";

    public CCDConfig() {
    }
  }
}
