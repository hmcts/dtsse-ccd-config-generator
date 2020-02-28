package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Properties;
import lombok.Data;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
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
    URLClassLoader l = (URLClassLoader) Thread.currentThread().getContextClassLoader();
    SourceSetContainer ssc = project.getConvention().getPlugin(JavaPluginConvention.class)
        .getSourceSets();
    SourceSet source = ssc.getByName("main");
    FileCollection deps = source.getRuntimeClasspath().plus(project.files((Object) l.getURLs()));

    JavaExec generate = project.getTasks().create("generateCCDConfig", JavaExec.class);
    generate.setGroup("CCD tasks");
    generate.setClasspath(deps);
    generate.setMain(Main.class.getName());
    generate.dependsOn(project.getTasksByName("compileJava", true));
    CCDConfig config = project.getExtensions().create("ccd", CCDConfig.class);
    config.configDir = project.getBuildDir();
    generate.doFirst(x -> generate.setArgs(Lists.newArrayList(
        config.configDir.getAbsolutePath(),
        config.rootPackage,
        config.caseType
    )));

    Properties properties = new Properties();
    try {
      properties.load(getClass().getClassLoader().getResourceAsStream("application.properties"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    String version = properties.getProperty("types.version");

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
