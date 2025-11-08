package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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

    try (ConfigurableApplicationContext context = application.run(args)) {

      File outputDir = new File(args[0]);
      context.getBean(CCDDefinitionGenerator.class).generateAllCaseTypesToJSON(outputDir);
    }
  }

  private static Class<?> findApplicationClass(String basePackage) {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootApplication.class));
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
