package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the two resolution rules that decide whether an INHERITED field can be configured per
 * subclass at all: which declarations are properties in the first place, and which class each
 * property's configuration is read through.
 *
 * <p>Both mirror the SDK, and both were wrong in the same measured way. {@code FieldUtils
 * .getCaseFields} dedupes by field NAME, because a name a subclass re-declares HIDES the
 * superclass's declaration and Jackson sees one property; the resolver deduped by composed CCD ID,
 * so sscs's {@code JointParty} — which re-declares five of {@code Entity}'s members purely to give
 * each a {@code @JsonProperty("jointParty…")} — resolved ten properties where the SDK sees five.
 * And {@code FieldUtils.ccdAnnotation} reads an inherited member's configuration through the class
 * the walk was ENTERED with, not the one declaring the field, which is what makes a class-level
 * {@code @CCD(member = …)} override findable.
 */
class PropertyResolverTest {

  @Test
  void aRedeclaredNameHidesTheSuperclassDeclaration(@TempDir Path work) throws Exception {
    // sscs's JointParty shape: the subclass re-declares an inherited member to rename its CCD id.
    Path src = work.resolve("src");
    write(src, "m", "Entity", "package m;\n\npublic abstract class Entity {\n"
        + "  private String name;\n}\n");
    write(src, "m", "JointParty", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\n\n"
        + "public class JointParty extends Entity {\n"
        + "  @JsonProperty(\"jointPartyName\")\n"
        + "  private String name;\n}\n");
    write(src, "m", "CaseData", "package m;\n\npublic class CaseData {\n"
        + "  private JointParty jointParty;\n}\n");

    PropertyResolver.Resolution resolution = resolve(src, "m.JointParty");

    assertThat(resolution.properties.keySet())
        .as("Java field hiding means one property, under the most-derived declaration's id")
        .containsExactly("jointPartyName");
  }

  @Test
  void anInheritedMemberRecordsBothItsDeclaringAndItsEnteredWithClass(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    write(src, "m", "Entity", "package m;\n\npublic abstract class Entity {\n"
        + "  private String name;\n}\n");
    write(src, "m", "Representative",
        "package m;\n\npublic class Representative extends Entity {\n}\n");
    write(src, "m", "CaseData", "package m;\n\npublic class CaseData {\n"
        + "  private Representative representative;\n}\n");

    ResolvedProperty name = resolve(src, "m.Representative").properties.get("name");

    assertThat(name.ownerFqn)
        .as("the annotation the patch edits lives on the declaration")
        .isEqualTo("m.Entity");
    assertThat(name.reachedThroughFqn)
        .as("but the class an override must be placed on is the one the walk was entered with")
        .isEqualTo("m.Representative");
  }

  @Test
  void aDirectMemberIsReachedThroughItsOwnClass(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    write(src, "m", "CaseData", "package m;\n\npublic class CaseData {\n"
        + "  private String applicantName;\n}\n");

    ResolvedProperty name = resolve(src, "m.CaseData").properties.get("applicantName");

    assertThat(name.ownerFqn).isEqualTo("m.CaseData");
    assertThat(name.reachedThroughFqn)
        .as("nothing inherited, so there is no scoping to do and the two agree")
        .isEqualTo("m.CaseData");
  }

  @Test
  void anUnwrappedMemberIsReachedThroughItsOwnDeclaredType(@TempDir Path work) throws Exception {
    // sscs holds JointParty @JsonUnwrapped with NO prefix, so its inherited members flatten to
    // top-level CaseField rows. The SDK walks the unwrapped type as its own getCaseFields call, so
    // their configuration is read through IT, not through CaseData.
    Path src = work.resolve("src");
    write(src, "m", "Entity", "package m;\n\npublic abstract class Entity {\n"
        + "  private String role;\n}\n");
    write(src, "m", "JointParty", "package m;\n\npublic class JointParty extends Entity {\n}\n");
    write(src, "m", "CaseData", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\n\n"
        + "public class CaseData {\n"
        + "  @JsonUnwrapped\n"
        + "  private JointParty jointParty;\n}\n");

    ResolvedProperty role = resolve(src, "m.CaseData").properties.get("role");

    assertThat(role.ownerFqn).isEqualTo("m.Entity");
    assertThat(role.reachedThroughFqn).isEqualTo("m.JointParty");
  }

  private static PropertyResolver.Resolution resolve(Path modelRoot, String rootFqn) {
    ModelSourceIndex index = ModelSourceIndex.parse(modelRoot.toAbsolutePath());
    return new PropertyResolver(index).resolve(index.byFqn(rootFqn).orElseThrow());
  }

  private static void write(Path root, String pkgPath, String simpleName, String body)
      throws Exception {
    Path dir = root.resolve(pkgPath);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(simpleName + ".java"), body);
  }
}
