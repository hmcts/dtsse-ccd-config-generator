package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins {@link NamingStrategy} against REAL Jackson: the retrofit patch pins a member's serialised name
 * as an explicit {@code @JsonProperty}, so this class's answer becomes the CCD field id the SDK
 * generates. A translation that differs from Jackson's by one character would silently change that id
 * — the exact failure mode {@link RetrofitPinnedNames} exists to prevent — so the transformation is
 * asserted equal to the strategy Jackson itself would apply, not to a hand-written expectation.
 */
class NamingStrategyTest {

  @TempDir
  private Path work;

  private Optional<NamingStrategy> strategyOf(String classBody) throws Exception {
    // A fresh source root per call: the index keys types by FQN, and every case here declares m.T.
    Path src = Files.createTempDirectory(work, "src").resolve("m");
    Files.createDirectories(src);
    Files.writeString(src.resolve("T.java"), classBody);
    ModelSourceIndex index = ModelSourceIndex.parse(src.getParent());
    return NamingStrategy.of(index.byFqn("m.T").orElseThrow());
  }

  private static String withNaming(String annotation) {
    return "package m;\n"
        + "import com.fasterxml.jackson.databind.PropertyNamingStrategies;\n"
        + "import com.fasterxml.jackson.databind.PropertyNamingStrategy;\n"
        + "import com.fasterxml.jackson.databind.annotation.JsonNaming;\n"
        + annotation + "\npublic class T {\n  private String addressLine1;\n}\n";
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "addressLine1", "postTown", "county", "hmctsDXNumber", "URL", "aB", "x", "applicant1FirstName",
      "isHmctsService", "sscsDocumentTranslationStatus", "aLongMixedUPPERCaseName",
  })
  void upperCamelCaseMatchesJacksonExactly(String fieldName) {
    assertThat(NamingStrategy.UPPER_CAMEL_CASE.idFor(fieldName))
        .isEqualTo(new PropertyNamingStrategies.UpperCamelCaseStrategy().translate(fieldName));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "addressLine1", "postTown", "county", "hmctsDXNumber", "URL", "aB", "x", "applicant1FirstName",
      "isHmctsService", "sscsDocumentTranslationStatus", "aLongMixedUPPERCaseName", "_leading",
  })
  void snakeCaseMatchesJacksonExactly(String fieldName) {
    assertThat(NamingStrategy.SNAKE_CASE.idFor(fieldName))
        .isEqualTo(new PropertyNamingStrategies.SnakeCaseStrategy().translate(fieldName));
  }

  @Test
  void readsBothTheSingleMemberAndTheNamedAnnotationForm() throws Exception {
    // Civil writes @JsonNaming(X.class); other teams write @JsonNaming(value = X.class). Both name the
    // same strategy, so both must resolve — a form this reader missed would silently drop to
    // refuse-to-guess and leave the rows as passthrough.
    assertThat(strategyOf(withNaming(
        "@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)")))
        .contains(NamingStrategy.UPPER_CAMEL_CASE);
    assertThat(strategyOf(withNaming(
        "@JsonNaming(value = PropertyNamingStrategies.SnakeCaseStrategy.class)")))
        .contains(NamingStrategy.SNAKE_CASE);
  }

  @Test
  void resolvesTheStrategyWhateverTheImportStyle() throws Exception {
    // The reference is matched on its TRAILING name, so a bare simple name, the nested-class form and
    // the fully-qualified form all resolve to the same strategy.
    assertThat(strategyOf(withNaming("@JsonNaming(UpperCamelCaseStrategy.class)")))
        .contains(NamingStrategy.UPPER_CAMEL_CASE);
    assertThat(strategyOf(withNaming(
        "@JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies"
            + ".UpperCamelCaseStrategy.class)")))
        .contains(NamingStrategy.UPPER_CAMEL_CASE);
    // Jackson's own deprecated spellings that teams still carry.
    assertThat(strategyOf(withNaming("@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)")))
        .contains(NamingStrategy.SNAKE_CASE);
    assertThat(strategyOf(withNaming("@JsonNaming(PropertyNamingStrategy.UPPER_CAMEL_CASE.class)")))
        .contains(NamingStrategy.UPPER_CAMEL_CASE);
  }

  @Test
  void refusesAnyStrategyItCannotEvaluateStatically() throws Exception {
    // A team's own strategy class (probate's RegularCaseNamingStrategy) is arbitrary Java. Guessing
    // would pin a WRONG @JsonProperty and change the CCD id, so it must yield empty and leave the
    // affected rows as verbatim passthrough. Same for a strategy whose transformation this converter
    // has no implementation of.
    assertThat(strategyOf(withNaming("@JsonNaming(RegularCaseNamingStrategy.class)"))).isEmpty();
    assertThat(strategyOf(withNaming("@JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)")))
        .isEmpty();
    assertThat(strategyOf(withNaming("@JsonNaming(PropertyNamingStrategies.LowerCaseStrategy.class)")))
        .isEmpty();
    // A non-class member value (nothing real writes this, but it must not throw).
    assertThat(strategyOf(withNaming("@JsonNaming(\"UpperCamelCase\")"))).isEmpty();
  }

  @Test
  void yieldsEmptyForATypeWithNoJsonNaming() throws Exception {
    assertThat(strategyOf("package m;\npublic class T {\n  private String addressLine1;\n}\n"))
        .isEmpty();
    assertThat(NamingStrategy.of(null)).isEmpty();
  }
}
