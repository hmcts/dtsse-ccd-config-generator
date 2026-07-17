package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * Pins {@link SynthesisPlacement}'s constructor-limit placement (finding B2). Builds throwaway model
 * source trees with the distinct Lombok shapes a real root class carries and asserts the placement
 * plan: {@code @SuperBuilder} never overflows (fpl's proof), {@code @Builder}/{@code @AllArgsConstructor}
 * overflows to a {@code CaseDataExtra} member, and the borderline {@code +1-tips-it} case nests the
 * synthesised fields into an existing prefix-less {@code @JsonUnwrapped} member's class (SSCS's shape)
 * so ZERO fields are added to the limited root.
 */
class SynthesisPlacementTest {

  private static List<FieldModel> synthFields(int n) {
    List<FieldModel> fields = new ArrayList<>();
    IntStream.range(0, n).forEach(i -> fields.add(FieldModel.builder()
        .id("extra" + i).javaName("extra" + i).fieldType("Text").javaType("String").build()));
    return fields;
  }

  private static void write(Path root, String pkgPath, String simpleName, String body)
      throws Exception {
    Path dir = root.resolve(pkgPath);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(simpleName + ".java"), body);
  }

  private String rootClass(String annotations, int fieldCount, String extraMembers) {
    StringBuilder sb = new StringBuilder();
    sb.append("package m;\n");
    sb.append("import com.fasterxml.jackson.annotation.JsonUnwrapped;\n");
    sb.append("import lombok.*;\n");
    sb.append("import lombok.experimental.SuperBuilder;\n");
    sb.append(annotations).append('\n');
    sb.append("public class CaseData {\n");
    for (int i = 0; i < fieldCount; i++) {
      sb.append("  private String f").append(i).append(";\n");
    }
    sb.append(extraMembers);
    sb.append("}\n");
    return sb.toString();
  }

  @Test
  void superBuilderNeverOverflows(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // @SuperBuilder generates a single builder-arg constructor, never a per-field one, so even 400
    // fields + 400 synthesised never trip the limit — no CaseDataExtra, no nesting (fpl's proof).
    write(src, "m", "CaseData", rootClass("@Data\n@SuperBuilder", 400, ""));
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    ModelSourceIndex.Type root = index.byFqn("m.CaseData").orElseThrow();

    SynthesisPlacement.Plan plan = new SynthesisPlacement(index, 250).plan(root, synthFields(400));

    assertThat(plan.overflow).as("@SuperBuilder must never overflow the constructor limit").isFalse();
    assertThat(plan.existingHost).isNull();
    assertThat(plan.extraClassName).isNull();
  }

  @Test
  void builderWithAllArgsOverflowsToCaseDataExtra(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // @Builder + @AllArgsConstructor at 100 fields with a 200-limit: 100 + 150 synthesised > 200
    // overflows, but the root (100) + 1 unwrapped member is well under 200, so it takes the
    // CaseDataExtra member (not the borderline host nesting).
    write(src, "m", "CaseData", rootClass("@Data\n@Builder\n@AllArgsConstructor", 100, ""));
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    ModelSourceIndex.Type root = index.byFqn("m.CaseData").orElseThrow();

    SynthesisPlacement.Plan plan = new SynthesisPlacement(index, 200).plan(root, synthFields(150));

    assertThat(plan.overflow).isTrue();
    assertThat(plan.borderlineStillOverLimit).isFalse();
    assertThat(plan.existingHost).isNull();
    assertThat(plan.extraClassName).isEqualTo("CaseDataExtra");
  }

  @Test
  void borderlinePlusOneTipNestsIntoExistingUnwrappedMember(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // A host complex type reachable via a prefix-less @JsonUnwrapped member with a resolvable getter
    // (Lombok @Data generates getFdcd()). Alphabetically-first eligible member name is "aaa".
    write(src, "m", "Host", "package m;\nimport lombok.Data;\n@Data\npublic class Host {\n"
        + "  private String h0;\n}\n");
    // Root at exactly the limit (250 fields, limit 250): adding even one @JsonUnwrapped CaseDataExtra
    // member (251 > 250) tips it over, so the fields must nest into the existing "aaa" member's class.
    String unwrapped =
        "  @JsonUnwrapped @Getter(AccessLevel.NONE) private Host aaa;\n"
        + "  public Host getAaa() { if (aaa == null) { aaa = new Host(); } return aaa; }\n";
    write(src, "m", "CaseData", rootClass("@Data\n@Builder\n@AllArgsConstructor", 250, unwrapped));
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    ModelSourceIndex.Type root = index.byFqn("m.CaseData").orElseThrow();

    SynthesisPlacement.Plan plan = new SynthesisPlacement(index, 250).plan(root, synthFields(5));

    assertThat(plan.overflow).isTrue();
    assertThat(plan.borderlineStillOverLimit).isTrue();
    assertThat(plan.extraClassName).as("no CaseDataExtra when nesting into an existing host").isNull();
    assertThat(plan.existingHost).isNotNull();
    assertThat(plan.existingHost.memberName).isEqualTo("aaa");
    assertThat(plan.existingHost.type.simpleName).isEqualTo("Host");
  }

  @Test
  void suffixesTheExtraClassNameOnlyForAForeignSameNamedType(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // A genuinely hand-written team class named CaseDataExtra sits in the model package: the overflow
    // companion the patch adds must NOT clash with it, so the name is suffixed to CaseDataExtra2.
    write(src, "m", "CaseDataExtra", "package m;\nimport lombok.Data;\n"
        + "/** A team-owned class, not the converter's. */\n@Data\n"
        + "public class CaseDataExtra {\n  private String teamField;\n}\n");
    write(src, "m", "CaseData", rootClass("@Data\n@Builder\n@AllArgsConstructor", 100, ""));
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    ModelSourceIndex.Type root = index.byFqn("m.CaseData").orElseThrow();

    SynthesisPlacement.Plan plan = new SynthesisPlacement(index, 200).plan(root, synthFields(150));

    assertThat(plan.overflow).isTrue();
    assertThat(plan.extraClassName)
        .as("a foreign same-named class must force a suffix").isEqualTo("CaseDataExtra2");
  }

  @Test
  void reusesTheExtraClassNameWhenTheSameNamedTypeIsOurOwnPriorCompanion(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // Bug B: a CaseDataExtra left in the model tree by a PRIOR converter run (carrying the overflow
    // marker) must be recognised as our own, so the name is REUSED (the patch recreates it in place)
    // rather than bumped to CaseDataExtra2 — which would desync the CaseData field from the freshly
    // generated event classes that reference the base name, and strand the old companion.
    write(src, "m", "CaseDataExtra", "package m;\nimport lombok.Data;\n"
        + "/**\n * Overflow companion.\n *\n * <p>" + SynthesisPlacement.EXTRA_CLASS_MARKER + "\n */\n"
        + "@Data\npublic class CaseDataExtra {\n  private String staleFromPriorRun;\n}\n");
    write(src, "m", "CaseData", rootClass("@Data\n@Builder\n@AllArgsConstructor", 100, ""));
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    ModelSourceIndex.Type root = index.byFqn("m.CaseData").orElseThrow();

    SynthesisPlacement.Plan plan = new SynthesisPlacement(index, 200).plan(root, synthFields(150));

    assertThat(plan.overflow).isTrue();
    assertThat(plan.extraClassName)
        .as("our own prior overflow companion must not force a suffix").isEqualTo("CaseDataExtra");
  }

  @Test
  void borderlineWithNoUsableHostFallsBackToCaseDataExtra(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // A @JsonCreator+@Builder host (B3 idiom) is NOT a usable synthesis host, and it is the only
    // prefix-less @JsonUnwrapped member — so the borderline case falls back to CaseDataExtra with the
    // still-over-limit flag (the maintainer must relocate a field by hand).
    write(src, "m", "Wrapper", "package m;\nimport com.fasterxml.jackson.annotation.JsonCreator;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\nimport lombok.Builder;\n"
        + "@Builder\npublic class Wrapper {\n  private String value;\n"
        + "  @JsonCreator public Wrapper(@JsonProperty(\"value\") String value) { this.value = value; }\n}\n");
    String unwrapped =
        "  @JsonUnwrapped @Getter(AccessLevel.NONE) private Wrapper w;\n"
        + "  public Wrapper getW() { return w; }\n";
    write(src, "m", "CaseData", rootClass("@Data\n@Builder\n@AllArgsConstructor", 250, unwrapped));
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    ModelSourceIndex.Type root = index.byFqn("m.CaseData").orElseThrow();

    SynthesisPlacement.Plan plan = new SynthesisPlacement(index, 250).plan(root, synthFields(5));

    assertThat(plan.overflow).isTrue();
    assertThat(plan.borderlineStillOverLimit).isTrue();
    assertThat(plan.existingHost).as("no @JsonCreator/@Builder host is usable").isNull();
    assertThat(plan.extraClassName).isEqualTo("CaseDataExtra");
  }
}
