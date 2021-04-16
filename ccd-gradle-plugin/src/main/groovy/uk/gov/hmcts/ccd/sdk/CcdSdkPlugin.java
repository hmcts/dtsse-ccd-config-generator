package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import lombok.Data;
import lombok.SneakyThrows;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

public class CcdSdkPlugin implements Plugin<Project> {

  public void apply(Project project) {
    project.getPlugins().apply(JavaPlugin.class);

    // Add the config generator's dependencies to the project.
    PomParser.getGeneratorDependencies().asMap().forEach((configuration, deps) -> {
      for (String dep : deps) {
        project.getDependencies().add(configuration, dep);
      }
    });

    // Extract the generator jar and add it to the project's dependencies.
    Provider<Directory> generatorDir =
        project.getLayout().getBuildDirectory().dir("generator");
    Task extractor = project.getTasks().create("extractGenerator").doLast((x) -> {
      extractGeneratorJar(generatorDir.get().file("generator.jar").getAsFile());
    });
    project.getDependencies().add("implementation",
        project.fileTree(generatorDir)
            .builtBy(extractor));

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
  private void extractGeneratorJar(File to) {
    try (InputStream is = CcdSdkPlugin.class.getClassLoader()
        .getResourceAsStream("generator/ccd-config-generator-DEV-SNAPSHOT.jar")) {
      byte[] buffer = new byte[is.available()];
      is.read(buffer);
      com.google.common.io.Files.createParentDirs(to);
      Files.write(to.toPath(), buffer);
    }
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
