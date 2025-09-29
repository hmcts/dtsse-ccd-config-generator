package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.util.Set;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

class Main {

  public static void main(String[] args) {
    Reflections reflections = new Reflections(new ConfigurationBuilder()
        .setUrls(ClasspathHelper.forPackage(args[1]))
        .setExpandSuperTypes(false));

    Set<Class<?>> types =
        reflections.getTypesAnnotatedWith(SpringBootApplication.class);
    if (types.size() != 1) {
      throw new RuntimeException("Expected a single SpringBootApplication but found "
          + types.size());
    }
    SpringApplication application = new SpringApplication(types.iterator().next());
    application.setWebApplicationType(WebApplicationType.NONE);

    try (ConfigurableApplicationContext context = application.run(args)) {

      File outputDir = new File(args[0]);
      context.getBean(CCDDefinitionGenerator.class).generateAllCaseTypesToJSON(outputDir);
    }
  }
}
