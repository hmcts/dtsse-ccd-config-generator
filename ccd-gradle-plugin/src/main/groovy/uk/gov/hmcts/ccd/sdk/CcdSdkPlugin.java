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
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

public class CcdSdkPlugin implements Plugin<Project> {

  public void apply(Project project) {
    project.getPlugins().apply(JavaPlugin.class);

    // Extract the config generator maven repo and add to the project.
    project.getRepositories().maven(x -> x.setUrl(extractGeneratorRepository(project)));

    // Add the dependency on the generator which will be fetched from the local maven repo.
    project.getDependencies().add("implementation", "com.github.hmcts:ccd-config-generator:"
        + getVersion());

    // Create the task to generate CCD config.
    JavaExec generate = project.getTasks().create("generateCCDConfig", JavaExec.class);
    generate.setGroup("CCD tasks");
    generate.getMainClass().set("uk.gov.hmcts.ccd.sdk.Main");

    SourceSetContainer ssc = project.getConvention().getPlugin(JavaPluginConvention.class)
        .getSourceSets();
    SourceSet main = ssc.getByName("main");
    generate.setClasspath(main.getRuntimeClasspath());

    CCDConfig config = project.getExtensions().create("ccd", CCDConfig.class);
    config.configDir = project.getObjects().directoryProperty();
    config.configDir.set(project.getBuildDir());
    // Register the config directory as a task output to use Gradle's up-to-date checking.
    generate.getOutputs().dir(config.configDir);

    // We must use an anonymous class here for Gradle's up to date checks to work.
    generate.doFirst(new Action<Task>() {
      @Override
      public void execute(Task task) {
        generate.setArgs(Arrays.asList(
            config.configDir.getAsFile().get().getAbsolutePath(),
            config.rootPackage,
            config.caseType
        ));
      }
    });


    project.getRepositories().mavenCentral();
    project.getRepositories().maven(x -> x.setUrl("https://jitpack.io"));

    project.afterEvaluate(p -> {
      if (config.decentralised) {
        project.getDependencies().add("implementation", "com.github.hmcts:data-runtime:"
            + getVersion());
        // Surface that we are decentralised to the spring boot apps.
        // This is an env var since it needs to be read beyond the application's classpath
        // to shut off the default cftlib elasticsearch indexer when decentralised)
        project.getTasks().withType(JavaExec.class).configureEach(t -> {
          if (t.getTaskIdentity().type.getName().equals("uk.gov.hmcts.rse.CftlibExec")) {
            t.getEnvironment().put("CCD_SDK_DECENTRALISED", "true");
          }
        });
      }
    });

  }

  @SneakyThrows
  private File extractGeneratorRepository(Project project) {
    DirectoryProperty buildDir = project.getLayout().getBuildDirectory();
    File archive = buildDir.file("generator.zip").get().getAsFile();
    try (InputStream is = CcdSdkPlugin.class.getClassLoader()
        .getResourceAsStream("generator/generator.zip")) {
      com.google.common.io.Files.createParentDirs(archive);
      Files.copy(is, archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    File generatorDir = buildDir.dir("config-generator-maven-repo").get().getAsFile();
    project.copy(x -> {
      x.from(project.zipTree(archive));
      x.into(generatorDir);
    });

    return generatorDir;
  }

  @SneakyThrows
  private String getVersion() {
    Properties properties = new Properties();
    properties.load(getClass().getClassLoader().getResourceAsStream("application.properties"));
    return properties.getProperty("types.version");
  }

  @Data
  static class CCDConfig {

    private DirectoryProperty configDir;
    private String rootPackage = "uk.gov.hmcts";
    private String caseType = "";
    private boolean decentralised = false;

    public CCDConfig() {
    }
  }
}
