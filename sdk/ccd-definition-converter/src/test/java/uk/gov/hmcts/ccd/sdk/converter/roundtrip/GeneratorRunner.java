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
 *
 * <p>The SDK side of the wiring lives HERE rather than in each caller's argument list, so the
 * harness context has exactly the shape {@code ApplicationEmitter} emits for a real service. The
 * two must not drift: a harness that resolves beans the emitted application cannot is a green test
 * over a broken generated app, which is precisely what happened before — callers each passed the
 * root {@code uk.gov.hmcts.ccd.sdk} package, which also holds the runtime callback layer
 * ({@code CallbackController}, {@code CcdCallbackExecutor}) whose constructor takes an
 * {@code @Autowired ObjectMapper}. That resolved only by accident, from the autoconfiguration the
 * emitted app no longer applies.
 */
final class GeneratorRunner {

  /**
   * The generator's own beans: {@code JSONConfigGenerator} plus the 24 sheet writers. Deliberately
   * NOT the root {@code uk.gov.hmcts.ccd.sdk} package — see the class javadoc.
   */
  static final String SDK_GENERATOR_PACKAGE = "uk.gov.hmcts.ccd.sdk.generator";

  /**
   * A {@code @Configuration} class in the root SDK package, so narrowing the scan to
   * {@link #SDK_GENERATOR_PACKAGE} would lose it. Registered by type instead, exactly as the
   * emitted application {@code @Import}s it.
   */
  static final String SDK_GENERATOR_CONFIG = "uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator";

  private GeneratorRunner() {
  }

  /**
   * Generates the CCD definition JSON for the given packages into an output directory.
   *
   * @param generatedClassLoader classloader exposing the compiled generated classes
   * @param outputDir directory the definition JSON tree is written to
   * @param configPackages base packages of the GENERATED model/config classes to component-scan.
   *     The SDK's own packages are added by this method and must not be passed in.
   */
  static void generate(ClassLoader generatedClassLoader, Path outputDir, String... configPackages) {
    String[] scanPackages = new String[configPackages.length + 1];
    scanPackages[0] = SDK_GENERATOR_PACKAGE;
    System.arraycopy(configPackages, 0, scanPackages, 1, configPackages.length);
    run(generatedClassLoader, outputDir, scanPackages);
  }

  private static void run(
      ClassLoader generatedClassLoader, Path outputDir, String[] scanPackages) {
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

      Class<?> generatorClass = generatedClassLoader.loadClass(SDK_GENERATOR_CONFIG);
      Method register = contextClass.getMethod("register", Class[].class);
      register.invoke(context, (Object) new Class<?>[] {generatorClass});

      Method refresh = contextClass.getMethod("refresh");
      refresh.invoke(context);

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
