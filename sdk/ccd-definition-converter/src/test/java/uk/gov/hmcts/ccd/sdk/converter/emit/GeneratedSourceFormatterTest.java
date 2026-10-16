package uk.gov.hmcts.ccd.sdk.converter.emit;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.lang.model.element.Modifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedSourceFormatterTest {

  private static final ClassName CCD = ClassName.get("uk.gov.hmcts.ccd.sdk.api", "CCD");
  private static final ClassName UNWRAPPED =
      ClassName.get("com.fasterxml.jackson.annotation", "JsonUnwrapped");

  private static String collapse(String... lines) {
    return GeneratedSourceFormatter.collapseAnnotations(String.join("\n", lines));
  }

  @Test
  void collapsesAShortAnnotationOntoOneLine() {
    assertThat(collapse(
        "    @JsonUnwrapped(",
        "            prefix = \"legal\"",
        "    )",
        "    private Legal legal;"))
        .isEqualTo("    @JsonUnwrapped(prefix = \"legal\")\n    private Legal legal;");
  }

  @Test
  void collapsesSeveralMembersAndKeepsArraysInline() {
    assertThat(collapse(
        "  @CCD(",
        "      label = \"Name\",",
        "      access = {A.class, B.class}",
        "  )"))
        .isEqualTo("  @CCD(label = \"Name\", access = {A.class, B.class})");
  }

  @Test
  void collapsesAMultiLineArrayToJavaPoetsInlineArrayForm() {
    assertThat(collapse(
        "@Ann({",
        "    A.class,",
        "    B.class",
        "})"))
        .isEqualTo("@Ann({A.class, B.class})");
  }

  @Test
  void leavesAnAnnotationMultiLineWhenItsSingleLineFormIsTooLong() {
    String label = "x".repeat(100);
    String source = String.join("\n",
        "    @CCD(",
        "            label = \"" + label + "\",",
        "            searchable = false",
        "    )",
        "    private String field;");
    assertThat(GeneratedSourceFormatter.collapseAnnotations(source)).isEqualTo(source);
  }

  @Test
  void fitsExactlyAtTheLimitButNotOneColumnOver() {
    // "    @A(" + "v = \"" + body + "\"" + ")" -> 4 + 3 + 5 + n + 1 + 1 = n + 14 columns.
    String fits = "y".repeat(120 - 14);
    assertThat(collapse("    @A(", "        v = \"" + fits + "\"", "    )"))
        .hasSize(120).doesNotContain("\n");
    String over = fits + "y";
    assertThat(collapse("    @A(", "        v = \"" + over + "\"", "    )")).contains("\n");
  }

  @Test
  void parenthesesAndBracesInsideStringLiteralsDoNotEndTheAnnotation() {
    assertThat(collapse(
        "  @CCD(",
        "      label = \"a) b ( c } { ] [\",",
        "      showCondition = \"x=\\\")\\\"\"",
        "  )",
        "  private String f;"))
        .isEqualTo("  @CCD(label = \"a) b ( c } { ] [\", showCondition = \"x=\\\")\\\"\")\n"
            + "  private String f;");
  }

  @Test
  void charLiteralsAreLexedToo() {
    assertThat(collapse("@A(", "    c = ')',", "    d = '\\''", ")"))
        .isEqualTo("@A(c = ')', d = '\\'')");
  }

  @Test
  void joinsJavaPoetsSplitMultiLineStringIntoAConcatenationOnOneLine() {
    assertThat(collapse(
        "    @CCD(",
        "            label = \"a\\n\"",
        "                    + \"b\"",
        "    )"))
        .isEqualTo("    @CCD(label = \"a\\n\" + \"b\")");
  }

  @Test
  void collapsesANestedAnnotationTogetherWithItsOuterOne() {
    assertThat(collapse(
        "@Outer(",
        "    inner = @Inner(",
        "        x = 1",
        "    ),",
        "    y = 2",
        ")"))
        .isEqualTo("@Outer(inner = @Inner(x = 1), y = 2)");
  }

  @Test
  void collapsesANestedAnnotationOnItsOwnWhenTheOuterOneIsTooLong() {
    String longValue = "z".repeat(120);
    assertThat(collapse(
        "@Outer(",
        "    inner = @Inner(",
        "        x = 1",
        "    ),",
        "    y = \"" + longValue + "\"",
        ")"))
        .isEqualTo(String.join("\n",
            "@Outer(",
            "    inner = @Inner(x = 1),",
            "    y = \"" + longValue + "\"",
            ")"));
  }

  @Test
  void leavesBlocksContainingCommentsOrTextBlocksAlone() {
    String lineComment = String.join("\n", "@A(", "    x = 1, // why", "    y = 2", ")");
    String blockComment = String.join("\n", "@A(", "    x = 1, /* why */", "    y = 2", ")");
    String textBlock = String.join("\n", "@A(", "    x = \"\"\"", "    a)", "    \"\"\"", ")");
    assertThat(GeneratedSourceFormatter.collapseAnnotations(lineComment)).isEqualTo(lineComment);
    assertThat(GeneratedSourceFormatter.collapseAnnotations(blockComment)).isEqualTo(blockComment);
    assertThat(GeneratedSourceFormatter.collapseAnnotations(textBlock)).isEqualTo(textBlock);
  }

  @Test
  void ignoresAnnotationLookalikesInsideTextBlocksAndComments() {
    String source = String.join("\n",
        "class X {",
        "  String s = \"\"\"",
        "      @A(",
        "          x = 1",
        "      )",
        "      \"\"\";",
        "  /*",
        "  @B(",
        "      y = 2",
        "  )",
        "  */",
        "}");
    assertThat(GeneratedSourceFormatter.collapseAnnotations(source)).isEqualTo(source);
  }

  @Test
  void leavesSingleLineAndUnbalancedAnnotationsUnchanged() {
    String single = "@A(x = 1)\n@B\nclass X {}";
    String unclosed = "@A(\n    x = 1\n";
    assertThat(GeneratedSourceFormatter.collapseAnnotations(single)).isEqualTo(single);
    assertThat(GeneratedSourceFormatter.collapseAnnotations(unclosed)).isEqualTo(unclosed);
  }

  @Test
  void formatsJavaPoetFieldAndTypeAnnotationsAndWritesWhereJavaPoetWould(@TempDir Path dir)
      throws Exception {
    TypeSpec type = TypeSpec.classBuilder("Sample").addModifiers(Modifier.PUBLIC)
        .addAnnotation(AnnotationSpec.builder(CCD).addMember("label", "$S", "Sample").build())
        .addField(FieldSpec.builder(String.class, "legal", Modifier.PRIVATE)
            .addAnnotation(AnnotationSpec.builder(UNWRAPPED).addMember("prefix", "$S", "legal").build())
            .addAnnotation(AnnotationSpec.builder(CCD)
                .addMember("label", "$S", "Label (with parens)")
                .addMember("access", "{$T.class, $T.class}", ClassName.get("p", "A"), ClassName.get("p", "B"))
                .build())
            .build())
        .build();
    JavaFile file = JavaFile.builder("uk.gov.hmcts.sample", type).build();
    // Sanity: this is the multi-line shape the formatter exists to fix.
    assertThat(file.toString()).contains("@JsonUnwrapped(\n");

    String formatted = GeneratedSourceFormatter.format(file);
    assertThat(formatted)
        .contains("\n@CCD(label = \"Sample\")\npublic class Sample {")
        .contains("  @JsonUnwrapped(prefix = \"legal\")\n")
        .contains("  @CCD(label = \"Label (with parens)\", access = {A.class, B.class})\n  private String legal;");
    assertThat(formatted.replaceAll("\\s", "")).isEqualTo(file.toString().replaceAll("\\s", ""));

    Path written = GeneratedSourceFormatter.writeTo(file, dir);
    assertThat(written).isEqualTo(dir.resolve("uk/gov/hmcts/sample/Sample.java"));
    assertThat(Files.readString(written, StandardCharsets.UTF_8)).isEqualTo(formatted);
  }
}
