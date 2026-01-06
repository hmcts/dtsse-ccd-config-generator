package uk.gov.hmcts.ccd.sdk;

import java.util.Arrays;
import lombok.Data;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenRepositoryContentDescriptor;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;

public class CcdSdkPlugin implements Plugin<Project> {

  public void apply(Project project) {
    project.getPlugins().apply(JavaPlugin.class);

    // Add the dependency on the generator
    project.getDependencies().add("implementation", "com.github.hmcts:ccd-config-generator:"
        + getVersion());

    // Create the task to generate CCD config.
    JavaExec generate = project.getTasks().create("generateCCDConfig", JavaExec.class);
    generate.setGroup("CCD tasks");
    generate.getMainClass().set("uk.gov.hmcts.ccd.sdk.Main");

    Configuration configGeneration = project.getConfigurations().maybeCreate("configGeneration");
    configGeneration.setCanBeConsumed(false);
    configGeneration.setCanBeResolved(true);
    configGeneration.setDescription("Dependencies used when generating CCD configuration");
    SourceSetContainer ssc = project.getExtensions()
      .getByType(JavaPluginExtension.class)
      .getSourceSets();
    generate.setClasspath(
        ssc.getByName("main").getRuntimeClasspath()
            .plus(configGeneration));

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


    project.afterEvaluate(p -> {
      String azureUrl = "https://pkgs.dev.azure.com/hmcts/Artifacts/_packaging/hmcts-lib/maven/v1";

      boolean azureRepoExists = project.getRepositories().stream()
          .anyMatch(repo -> repo instanceof MavenArtifactRepository
          && ((MavenArtifactRepository) repo).getUrl().toString().equals(azureUrl));

      if (!azureRepoExists) {
        project.getRepositories().maven(x -> {
          x.setUrl(azureUrl);
          x.setName("HMCTS Azure artifacts dependency repository added by the CCD SDK plugin");
          x.mavenContent(MavenRepositoryContentDescriptor::releasesOnly);
        });
      }

      if (config.decentralised) {
        // This is a signal to the cftlib to link in the feature branch
        // TODO: remove on landing
        project.getExtensions().getExtraProperties().set("cftlib.datastore", "decentralised");
        String version = getVersion();
        project.getDependencies().add("implementation", "com.github.hmcts:decentralised-runtime:"
            + version);
        String dependencyNotation = "com.github.hmcts:cftlib-dev-only:" + version;
        if (config.runtimeIndexing) {
          project.getDependencies().add("implementation", dependencyNotation);
        } else {
          project.getPluginManager().withPlugin("com.github.hmcts.rse-cft-lib", plugin ->
              project.getDependencies().add("cftlibImplementation", dependencyNotation));
        }
        // Surface that we are decentralised to the spring boot apps.
        // This is an env var since it needs to be read beyond the application's classpath
        // to shut off the default cftlib elasticsearch indexer when decentralised)
        project.getTasks().withType(JavaExec.class).configureEach(t -> {
          if (t.getTaskIdentity().type.getName().equals("uk.gov.hmcts.rse.CftlibExec")) {
            t.getEnvironment().put("CCD_SDK_DECENTRALISED", "true");
          }
        });
      }

      if (config.caseEventServiceBus) {
        project.getDependencies().add("implementation", "com.github.hmcts:ccd-servicebus-support:"
            + getVersion());
      }
    });
  }

  private String getVersion() {
    String implementationVersion = getClass().getPackage().getImplementationVersion();
    return implementationVersion != null ? implementationVersion : "DEV-SNAPSHOT";
  }

  @Data
  static class CCDConfig {

    private DirectoryProperty configDir;
    private String rootPackage = "uk.gov.hmcts";
    private String caseType = "";
    private boolean decentralised = false;
    private boolean caseEventServiceBus = false;
    private boolean runtimeIndexing = false;

    public CCDConfig() {
    }
  }
}
