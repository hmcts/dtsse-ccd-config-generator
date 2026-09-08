package uk.gov.hmcts.ccd.sdk.docweave;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.devtools.restart.classloader.RestartClassLoader;
import org.testcontainers.containers.PostgreSQLContainer;

class DocweaveRestartIntegrationTest {

  @Test
  void migratesDocweaveOnColdStartAndPreservesTemplatesAcrossRestarts() throws Exception {
    List<URL> classpath = new ArrayList<>();
    for (String entry : System.getProperty("docweave.restart-test.classpath").split(File.pathSeparator)) {
      classpath.add(new File(entry).toURI().toURL());
    }
    try (var database = new PostgreSQLContainer<>("postgres:15-alpine");
         var base = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      database.start();
      var previous = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(base);
        var settingsClass = base.loadClass("org.springframework.boot.devtools.settings.DevToolsSettings");
        var settings = settingsClass.getMethod("get").invoke(null);
        List<URL> restartUrls = new ArrayList<>();
        for (URL url : classpath) {
          if (new File(url.toURI()).isDirectory()
              || (boolean) settingsClass.getMethod("isRestartInclude", URL.class).invoke(settings, url)) {
            restartUrls.add(url);
          }
        }

        // Force the base-loader copies to exist before startup, as they do in bootWithCCD.
        // The result must not depend on which loader happens to resolve these types first.
        base.loadClass(DocweaveFlywayAutoConfiguration.class.getName()).getDeclaredMethods();
        for (int restart = 0; restart < 3; restart++) {
          try (var loader = new RestartClassLoader(base, restartUrls.toArray(URL[]::new))) {
            Thread.currentThread().setContextClassLoader(loader);
            var application = loader.loadClass(RestartMigrationApplication.class.getName());
            var count = application.getMethod("migrateAndCountTemplates", String.class, String.class, String.class)
                .invoke(null, database.getJdbcUrl(), database.getUsername(), database.getPassword());
            assertThat(count).isEqualTo(1);
          }
        }
      } finally {
        Thread.currentThread().setContextClassLoader(previous);
      }
    }
  }
}
