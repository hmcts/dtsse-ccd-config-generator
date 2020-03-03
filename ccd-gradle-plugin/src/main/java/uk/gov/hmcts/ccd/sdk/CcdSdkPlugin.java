package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.util.Arrays;
import java.util.Properties;
import lombok.Data;
import lombok.SneakyThrows;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * A simple 'hello world' plugin.
 */
public class CcdSdkPlugin implements Plugin<Project> {

  public void apply(Project project) {
    project.getPlugins().apply(JavaPlugin.class);

    String version = getVersion();
    Dependency generator = project.getDependencies()
        .create("uk.gov.hmcts.reform:ccd-config-generator:" + version);

    Configuration generatorConfiguration = project.getConfigurations()
        .create("ccd-config-generator");
    generatorConfiguration.getDependencies().add(generator);
    project.getDependencies().add("compile", "uk.gov.hmcts.reform:ccd-sdk-types:" + version);

    JavaExec generate = project.getTasks().create("generateCCDConfig", JavaExec.class);
    generate.setGroup("CCD tasks");
    generate.setMain("uk.gov.hmcts.ccd.sdk.Main");

    SourceSetContainer ssc = project.getConvention().getPlugin(JavaPluginConvention.class)
        .getSourceSets();
    SourceSet main = ssc.getByName("main");
    generate.setClasspath(main.getRuntimeClasspath().plus(generatorConfiguration));

    CCDConfig config = project.getExtensions().create("ccd", CCDConfig.class);
    config.configDir = project.getBuildDir();

    generate.doFirst(x -> generate.setArgs(Arrays.asList(
        config.configDir.getAbsolutePath(),
        config.rootPackage,
        config.caseType
    )));

    if (System.getenv("GRADLE_FUNCTIONAL_TEST") != null) {
      project.getRepositories().mavenLocal();
    } else {
      project.getRepositories().maven(x -> x.setUrl("https://dl.bintray.com/hmcts/hmcts-maven"));
    }
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
