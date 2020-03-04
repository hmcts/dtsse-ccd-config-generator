package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

public class Main {

  public static void main(String[] args) {
    File outputDir = new File(args[0]);
    Reflections reflections = new Reflections(new ConfigurationBuilder()
        .setUrls(ClasspathHelper.forPackage(args[1]))
        .setExpandSuperTypes(false));
    ConfigGenerator generator = new ConfigGenerator(reflections, args[1]);
    generator.resolveConfig(outputDir);
    // Required on Gradle 4.X or build task hangs.
    System.exit(0);
  }
}
