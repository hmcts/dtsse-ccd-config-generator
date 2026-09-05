package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Compiles a tree of generated {@code .java} sources with the running test JVM's classpath,
 * running the Lombok annotation processor so the {@code @Data}/{@code @Builder} case data
 * classes gain the accessors the SDK generator reflects over.
 *
 * <p>Returns a classloader over the compiled output whose parent is the test classloader, so
 * generated code links against the very same {@code ccd-config-generator} classes the test
 * uses.
 */
final class GeneratedSourceCompiler {

  private GeneratedSourceCompiler() {
  }

  /**
   * Compiles all {@code .java} files under a source root into a fresh output directory.
   *
   * @param sourceRoot the root of the generated source tree
   * @param classesOut the directory compiled classes are written to (created if absent)
   * @return a classloader exposing the compiled classes over the test classpath
   */
  static ClassLoader compile(Path sourceRoot, Path classesOut) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException(
          "No system Java compiler available; the round-trip test requires a JDK, not a JRE.");
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
             compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {

      Files.createDirectories(classesOut);
      List<Path> sources = listJavaSources(sourceRoot);
      Iterable<? extends JavaFileObject> units =
          fileManager.getJavaFileObjectsFromPaths(sources);
      fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesOut));

      List<String> options = new ArrayList<>(List.of(
          "-classpath", System.getProperty("java.class.path"),
          // Lombok is on the classpath and discovered as a processor; -proc:full keeps
          // annotation processing on under JDKs that would otherwise warn or disable it.
          "-proc:full"));

      JavaCompiler.CompilationTask task =
          compiler.getTask(null, fileManager, diagnostics, options, null, units);
      // Large generated case types (hundreds of files, deep complex-type graphs) can exhaust
      // the default compiler thread stack, so run the compile on a thread with a big stack.
      boolean success = callWithLargeStack(task);

      if (!success) {
        StringBuilder report = new StringBuilder("Compilation of generated sources failed:\n");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
          if (d.getKind() == Diagnostic.Kind.ERROR) {
            report.append("  ").append(d).append('\n');
          }
        }
        throw new IllegalStateException(report.toString());
      }
      return java.net.URLClassLoader.newInstance(
          new java.net.URL[] {classesOut.toUri().toURL()},
          GeneratedSourceCompiler.class.getClassLoader());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to compile generated sources under " + sourceRoot, e);
    }
  }

  private static boolean callWithLargeStack(JavaCompiler.CompilationTask task) {
    boolean[] result = new boolean[1];
    RuntimeException[] failure = new RuntimeException[1];
    Thread thread = new Thread(null, () -> {
      try {
        result[0] = task.call();
      } catch (RuntimeException e) {
        failure[0] = e;
      }
    }, "generated-source-compiler", 256L * 1024 * 1024);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while compiling generated sources", e);
    }
    if (failure[0] != null) {
      throw failure[0];
    }
    return result[0];
  }

  private static List<Path> listJavaSources(Path sourceRoot) throws IOException {
    try (Stream<Path> walk = Files.walk(sourceRoot)) {
      return walk
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .toList();
    }
  }
}
