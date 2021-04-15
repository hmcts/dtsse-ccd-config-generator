package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.reflections.Reflections;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;

class Main {

  public static void main(String[] args) {
    File outputDir = new File(args[0]);

    Reflections reflections = new Reflections(args[1]);
    List<CCDConfig> configs = reflections
        .getSubTypesOf(CCDConfig.class)
        .stream()
        .map(configClass -> getCcdConfig(configClass))
        .flatMap(Optional::stream)
        .collect(Collectors.toList());

    ConfigGenerator generator = new ConfigGenerator(configs);
    generator.resolveConfig(outputDir);
    // Required on Gradle 4.X or build task hangs.
    System.exit(0);
  }

  @SneakyThrows
  private static Optional<CCDConfig> getCcdConfig(Class<? extends CCDConfig> configClass) {
    try {
      return Optional.of(configClass.getDeclaredConstructor().newInstance());
    } catch (NoSuchMethodException e) {
      return Optional.empty();
    }
  }
}
