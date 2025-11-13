package uk.gov.hmcts.reform.cftlib.pdf;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

final class ResourceLoader {

  private ResourceLoader() {
  }

  static String loadString(String resourcePath) {
    return new String(loadBytes(resourcePath), UTF_8);
  }

  static byte[] loadBytes(String resourcePath) {
    try {
      URL resource = ResourceLoader.class.getResource(resourcePath);
      return Files.readAllBytes(Paths.get(resource.toURI()));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
