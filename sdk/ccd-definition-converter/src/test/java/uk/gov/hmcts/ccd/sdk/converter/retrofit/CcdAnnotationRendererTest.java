package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * Unit test for {@link CcdAnnotationRenderer}'s line-wrapping (annotation-placement fix): a
 * single-line {@code @CCD(...)} that would exceed {@link CcdAnnotationRenderer#MAX_LINE_LENGTH}
 * columns once placed at the field's indent is wrapped one member per continuation line instead —
 * mirroring the model-emit golden fixture's house style — so the retrofit patch never hands a
 * team's checkstyle a line over its limit.
 */
class CcdAnnotationRendererTest {

  private final CcdAnnotationRenderer renderer = new CcdAnnotationRenderer("uk.gov.hmcts.example.config");

  private static FieldModel.FieldModelBuilder field() {
    return FieldModel.builder().id("x").javaName("x").fieldType("Text");
  }

  @Test
  void rendersASingleLineFormWhenItFitsTheLimit() {
    FieldModel field = field().label("Short label").build();
    String rendered = renderer.render(field, 4);
    assertThat(rendered).isEqualTo("@CCD(label = \"Short label\")");
  }

  @Test
  void wrapsOneMemberPerContinuationLineWhenTheSingleLineFormExceedsTheLimit() {
    // No single member is individually too long, but label + hint + typeOverride + access
    // together push the single-line form well past 120 columns at a shallow (4-space) indent.
    FieldModel field = field()
        .label("Is the Probate practitioner named in the will as an executor?")
        .hint("Answer based on the will you have uploaded")
        .typeOverride("YesOrNo")
        .accessClassNames(List.of("Access21"))
        .build();
    String rendered = renderer.render(field, 4);

    assertThat(rendered).startsWith("@CCD(\n");
    assertThat(rendered).endsWith("\n)");
    List<String> lines = List.of(rendered.split("\n", -1));
    // One member per line, each ending in a comma except the last, and every line within the limit.
    assertThat(lines).hasSize(6); // "@CCD(", label, hint, typeOverride, access, ")"
    assertThat(lines.get(1)).endsWith(",").contains("label =");
    assertThat(lines.get(2)).endsWith(",").contains("hint =");
    assertThat(lines.get(3)).endsWith(",").contains("typeOverride = FieldType.YesOrNo");
    assertThat(lines.get(4)).doesNotEndWith(",").contains("access = {Access21.class}");
    assertThat(lines).allSatisfy(line -> assertThat(4 + line.length())
        .isLessThanOrEqualTo(CcdAnnotationRenderer.MAX_LINE_LENGTH));
  }

  @Test
  void wrapDecisionAccountsForTheCallersIndentNotJustTheAnnotationItself() {
    // The same member set fits at a shallow indent but not once placed deep enough that the
    // indent alone plus the single-line form would cross the limit — the wrap decision must use
    // baseIndentLength, not a hardcoded assumption about where the annotation lands.
    FieldModel field = field()
        .label("A moderately long label that alone is fine at a shallow indent")
        .build();
    String shallow = renderer.render(field, 4);
    String deep = renderer.render(field, 100);

    assertThat(shallow).doesNotContain("\n");
    assertThat(deep).contains("\n");
  }

  @Test
  void returnsNullWhenNoMemberIsWarranted() {
    FieldModel field = field().build();
    assertThat(renderer.render(field, 4)).isNull();
  }

  @Test
  void wrapsALongAccessArrayAcrossContinuationLines() {
    // A field composed of many access classes makes the access member itself long; it is laid on its
    // own continuation line (one member per line) so no line blows the house limit at the field indent.
    FieldModel field = field()
        .label("A field")
        .accessClassNames(List.of(
            "DefaultAccess", "CaseworkerCruAccess", "CitizenRAccess",
            "SolicitorCruAccess", "SystemUpdateCrudAccess"))
        .build();

    String rendered = renderer.render(field, 4);

    assertThat(rendered).startsWith("@CCD(\n");
    assertThat(rendered).contains("access = {DefaultAccess.class, CaseworkerCruAccess.class");
  }
}
