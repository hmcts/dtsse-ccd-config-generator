package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Runs the ccd-config-generator over freshly compiled generated classes, producing a CCD
 * definition JSON tree.
 *
 * <p>Reflection is used deliberately: the generated {@code CCDConfig} classes are loaded by a
 * child classloader (over the compiled output), so a fresh Spring context is created with that
 * classloader as its bean classloader and scans both the SDK packages and the generated
 * packages. The generator bean is then invoked reflectively to avoid a hard compile-time link
 * to types the child classloader owns.
 */
final class GeneratorRunner {

  private GeneratorRunner() {
  }

  /**
   * Generates the CCD definition JSON for the given packages into an output directory.
   *
   * @param generatedClassLoader classloader exposing the compiled generated classes
   * @param outputDir directory the definition JSON tree is written to
   * @param scanPackages base packages to component-scan (SDK + generated model/config)
   */
  static void generate(ClassLoader generatedClassLoader, Path outputDir, String... scanPackages) {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(generatedClassLoader);
    try {
      Class<?> contextClass = generatedClassLoader.loadClass(
          "org.springframework.context.annotation.AnnotationConfigApplicationContext");
      Object context = contextClass.getDeclaredConstructor().newInstance();

      Method setClassLoader = contextClass.getMethod("setClassLoader", ClassLoader.class);
      setClassLoader.invoke(context, generatedClassLoader);

      Method scan = contextClass.getMethod("scan", String[].class);
      scan.invoke(context, (Object) scanPackages);

      Method refresh = contextClass.getMethod("refresh");
      refresh.invoke(context);

      Class<?> generatorClass =
          generatedClassLoader.loadClass("uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator");
      Method getBean = contextClass.getMethod("getBean", Class.class);
      Object generator = getBean.invoke(context, generatorClass);

      Method generateAll =
          generatorClass.getMethod("generateAllCaseTypesToJSON", File.class);
      generateAll.invoke(generator, outputDir.toFile());

      contextClass.getMethod("close").invoke(context);
    } catch (ReflectiveOperationException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new IllegalStateException(
          "Failed to run the CCD config generator over generated classes: " + cause.getMessage(),
          cause);
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }
}
