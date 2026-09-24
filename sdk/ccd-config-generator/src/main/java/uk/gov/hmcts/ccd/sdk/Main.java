package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

class Main {

  public static void main(String[] args) {
    Class<?> applicationClass = findApplicationClass(args[1]);
    SpringApplication application = new SpringApplication(applicationClass);

    // Pick a random port to avoid conflicts
    Map<String, Object> defaults = new HashMap<>();
    defaults.putIfAbsent("server.port", "0");
    defaults.putIfAbsent("server.address", "127.0.0.1");
    application.setDefaultProperties(defaults);

    // Spring Boot devtools, if it is on the service's runtime classpath, relaunches the application
    // on its own restart classloader in a separate "restartedMain" thread and returns control here
    // immediately — so this thread closes the context while the app is still starting and the
    // generator never runs (observed on prl-cos-api, which depends on devtools: the JVM exited 1
    // having written no definition). Generation is a one-shot batch run with nothing to reload, so
    // restart is always unwanted here. Set as a system property rather than a default property
    // because devtools reads it before the environment is prepared.
    System.setProperty("spring.devtools.restart.enabled", "false");

    try (ConfigurableApplicationContext context = application.run(args)) {

      File outputDir = new File(args[0]);
      context.getBean(CCDDefinitionGenerator.class).generateAllCaseTypesToJSON(outputDir);
    }
  }

  private static Class<?> findApplicationClass(String basePackage) {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    // @SpringBootConfiguration rather than @SpringBootApplication, so an entry point that opts out of
    // autoconfiguration still qualifies. @SpringBootApplication is meta-annotated with it (the filter
    // considers meta-annotations), so a service's own application class matches exactly as before;
    // this only widens the match to include a plain @SpringBootConfiguration + @ComponentScan class,
    // which is what the converter emits for retrofit runs on a real service classpath.
    scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootConfiguration.class));
    var candidates = scanner.findCandidateComponents(basePackage);
    if (candidates.size() != 1) {
      throw new RuntimeException("Expected a single SpringBootApplication but found "
          + candidates.size());
    }
    BeanDefinition definition = candidates.iterator().next();
    String beanClassName = definition.getBeanClassName();
    if (beanClassName == null) {
      throw new RuntimeException("Unable to resolve SpringBootApplication class for " + basePackage);
    }
    return ClassUtils.resolveClassName(beanClassName, Main.class.getClassLoader());
  }
}
