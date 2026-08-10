package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;

/**
 * Pins {@link RetrofitFixedListLabels}'s handling of a {@code @JsonValue} enum — one that emits a
 * constructor field rather than its constant name.
 *
 * <p>Such an enum's {@code ListElementCode}s are already right and need no pin (which is why
 * {@code match} refuses it), but nothing about a constant's NAME says which definition row it carries,
 * so its labels used to fall back and the list emitted {@code ListElement == ListElementCode}. sscs's
 * {@code SendToFirstTierActions} is the case: {@code DECISION_REMADE} emits {@code remade}.
 */
class RetrofitFixedListLabelsTest {

  private static ModelSourceIndex.Type enumType(Path src, String source) throws Exception {
    Files.createDirectories(src.resolve("m"));
    Files.writeString(src.resolve("m/Action.java"), source);
    return ModelSourceIndex.parse(src).byFqn("m.Action").orElseThrow();
  }

  private static FixedListModel list(String... codeLabelPairs) {
    List<FixedListModel.Item> items = new java.util.ArrayList<>();
    for (int i = 0; i < codeLabelPairs.length; i += 2) {
      items.add(FixedListModel.Item.builder()
          .code(codeLabelPairs[i])
          .label(codeLabelPairs[i + 1])
          .javaConstant(codeLabelPairs[i].toUpperCase(java.util.Locale.ROOT))
          .displayOrder(i / 2 + 1)
          .build());
    }
    return FixedListModel.builder().id("FL_action").javaClassName("Action").items(items).build();
  }

  /**
   * sscs's shape: the CCD code is a constructor field, serialised through a {@code @JsonValue}.
   */
  private static final String JSON_VALUE_ENUM = "package m;\n"
      + "import com.fasterxml.jackson.annotation.JsonValue;\n"
      + "public enum Action {\n"
      + "  DECISION_REMADE(\"remade\", \"Decision remade by UT\"),\n"
      + "  DECISION_REFUSED(\"refused\", \"Decision refused by UT\"),\n"
      + "  DECISION_REMITTED(\"remitted\", \"Remit from UT\");\n"
      + "  private final String ccdDefinition;\n"
      + "  private final String description;\n"
      + "  Action(String ccdDefinition, String description) {\n"
      + "    this.ccdDefinition = ccdDefinition;\n"
      + "    this.description = description;\n"
      + "  }\n"
      + "  @Override\n  @JsonValue\n  public String toString() { return ccdDefinition; }\n"
      + "}\n";

  @Test
  void pinsLabelsOnAJsonValueEnumByResolvingItsCodeCarryingArgument(@TempDir Path work)
      throws Exception {
    ModelSourceIndex.Type type = enumType(work.resolve("src"), JSON_VALUE_ENUM);

    // Position 0 provably carries the codes (one-to-one, covering every row), so each constant's row —
    // and therefore its definition label — is known. Without that resolution all three labels fell back
    // to the code and cost three residual lines.
    assertThat(RetrofitFixedListLabels.pins(type,
        list("remade", "Remade", "refused", "Refused", "remitted", "Remit from UT")))
        .containsEntry("DECISION_REMADE", "Remade")
        .containsEntry("DECISION_REFUSED", "Refused")
        .containsEntry("DECISION_REMITTED", "Remit from UT");
  }

  @Test
  void pinsNoLabelWhereTheEmittedCodeAlreadyIsTheDefinitionsLabel(@TempDir Path work)
      throws Exception {
    ModelSourceIndex.Type type = enumType(work.resolve("src"), JSON_VALUE_ENUM);

    // ListElement == ListElementCode for two rows: the generator's own fallback emits those, so a pin
    // would be noise. Only the diverging row is pinned.
    assertThat(RetrofitFixedListLabels.pins(type,
        list("remade", "remade", "refused", "refused", "remitted", "Remit from UT")))
        .containsExactly(java.util.Map.entry("DECISION_REMITTED", "Remit from UT"));
  }

  @Test
  void makesNoCodePinOnAJsonValueEnum(@TempDir Path work) throws Exception {
    ModelSourceIndex.Type type = enumType(work.resolve("src"), JSON_VALUE_ENUM);
    FixedListModel model = list("remade", "Remade", "refused", "Refused", "remitted", "Remit from UT");

    // A @JsonValue takes precedence over @JsonProperty, so nothing pinned on a constant can move the
    // code — and it needs no moving here: the enum already emits the definition's own codes.
    assertThat(RetrofitFixedListLabels.codePins(type, model)).isEmpty();
    assertThat(RetrofitFixedListLabels.constantsToAdd(type, model)).isEmpty();
    assertThat(RetrofitFixedListLabels.canEmitTheDefinitionsCodes(type, model)).isFalse();
  }

  @Test
  void pinsNothingWhenNoArgumentPositionCarriesTheDefinitionsCodes(@TempDir Path work)
      throws Exception {
    // Position 0 holds a template name, not a code, and no other position covers the list — so which
    // constant carries which row is not established and no label is written on a guess.
    ModelSourceIndex.Type type = enumType(work.resolve("src"), "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonValue;\n"
        + "public enum Action {\n"
        + "  ONE(\"tmpl-one\"),\n  TWO(\"tmpl-two\");\n"
        + "  private final String template;\n"
        + "  Action(String template) { this.template = template; }\n"
        + "  @Override\n  @JsonValue\n  public String toString() { return template; }\n"
        + "}\n");

    assertThat(RetrofitFixedListLabels.pins(type, list("remade", "Remade", "refused", "Refused")))
        .isEmpty();
  }
}
