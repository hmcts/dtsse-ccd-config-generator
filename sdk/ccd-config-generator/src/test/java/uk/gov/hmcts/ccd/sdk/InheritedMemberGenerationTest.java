package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.ccd.sdk.diff.CcdConfigComparator;

/**
 * Golden test for class-level {@code @CCD(member = ...)} (see
 * {@link uk.gov.hmcts.reform.InheritedMemberCaseType}). A field declared once on a shared superclass
 * is one Java member but several CCD members, and a hand-written definition configures those rows
 * independently — so each subclass states its own, and the base declaration serves whoever overrides
 * nothing.
 *
 * <p>Read through the owning class at the same three sites that already read {@code @CCD}: the
 * metadata a row carries ({@code CaseFieldGenerator.populateFieldMetadata}), whether the row exists
 * at all ({@code FieldUtils.getCaseFields}), and the access it derives
 * ({@code AuthorisationCaseFieldGenerator.addPermissionsFromFields}).
 */
@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class InheritedMemberGenerationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Autowired
    private CCDDefinitionGenerator generator;

    @Test
    @SneakyThrows
    public void generatesPerSubclassInheritedMemberConfiguration() {
        File out = tmp.newFolder();
        generator.generateAllCaseTypesToJSON(out);

        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(out, "InheritedMember"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap("InheritedMember");
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    /**
     * The three outcomes stated directly as well as via the snapshot, so the intent survives a
     * snapshot refresh: the override applies to the class declaring it, the base declaration still
     * serves a subclass that overrides nothing, and {@code ignore} drops the member from that class
     * alone.
     */
    @Test
    @SneakyThrows
    public void scopesAnOverrideToTheSubclassThatDeclaresIt() {
        File out = tmp.newFolder();
        generator.generateAllCaseTypesToJSON(out);
        File complexTypes = new File(out, "InheritedMember/ComplexTypes");

        assertThat(new File(complexTypes, "InheritedMemberRepresentative.json"))
            .content()
            .contains("hasRepresentative=\\\"Yes\\\"")
            .contains("Representative organisation");

        assertThat(new File(complexTypes, "InheritedMemberAppellant.json"))
            .content()
            .doesNotContain("FieldShowCondition")
            .contains("Organisation");

        // The joint party is reached @JsonUnwrapped, and an unwrapped type emits no ComplexTypes
        // rows at all — its members are the case type's own fields. Its dropped member is asserted
        // on the CaseField path below, which is the only place it can show.
        assertThat(complexTypes.list()).doesNotContain("InheritedMemberJointParty.json");
    }

    /**
     * The dropped member is gone from the {@code CaseField} rows the prefix-less
     * {@code @JsonUnwrapped} joint party flattens into, which is the path sscs's residual rows came
     * down: a member dropped only in the {@code ComplexTypes} view would still emit a top-level
     * field the definition has no counterpart for.
     */
    @Test
    @SneakyThrows
    public void dropsAnIgnoredInheritedMemberFromTheUnwrappedCaseFields() {
        File out = tmp.newFolder();
        generator.generateAllCaseTypesToJSON(out);
        File caseType = new File(out, "InheritedMember");

        assertThat(new File(caseType, "CaseField.json"))
            .content()
            .contains("hasJointParty")
            .contains("partyName")
            .doesNotContain("organisation");

        // Nor may a dangling grant survive for a field that emitted no row.
        assertThat(new File(caseType, "AuthorisationCaseField/caseworker-publiclaw-solicitor.json"))
            .content().doesNotContain("organisation");
    }

    /**
     * A type reachable only through an override is reachable: {@code ConfigResolver}'s walk reads
     * {@code @CCD} through the class it entered with too, so a member whose declaration is
     * {@code ignore = true} for everyone else still reaches the list the one subclass that has a row
     * for it names.
     *
     * <p>This was the sscs {@code FL_ibcRoles} regression, and it only appears once overrides exist:
     * moving a claim off a shared declaration is exactly what leaves that declaration ignored.
     */
    @Test
    @SneakyThrows
    public void reachesAListNamedOnlyByAnOverride() {
        File out = tmp.newFolder();
        generator.generateAllCaseTypesToJSON(out);
        File caseType = new File(out, "InheritedMember");

        assertThat(new File(caseType, "FixedLists/FL_inheritedMemberRoles.json"))
            .as("the list the override names must emit its rows")
            .content()
            .contains("I am appealing for myself")
            .contains("I am appealing as a guardian");

        assertThat(new File(caseType, "ComplexTypes/InheritedMemberAppellant.json"))
            .as("and the row it types carries the override's whole configuration")
            .content()
            .contains("Appellant role")
            .contains("FL_inheritedMemberRoles");

        assertThat(new File(caseType, "ComplexTypes/InheritedMemberRepresentative.json"))
            .as("while the subclasses the definition has no role row for still drop it")
            .content()
            .doesNotContain("role");
    }

    /**
     * An override naming a field the class declares itself, or one no supertype declares, is inert —
     * so it fails generation rather than silently leaving a definition short a column.
     */
    @Test
    public void failsWhenAnOverrideNamesNothingItCanConfigure() {
        assertThatThrownBy(() -> FieldUtils.getCaseFields(SelfDeclared.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("declares it itself");

        assertThatThrownBy(() -> FieldUtils.getCaseFields(NoSuchMember.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no supertype declares that field");
    }

    @uk.gov.hmcts.ccd.sdk.api.CCD(member = "own", label = "Nope")
    private static class SelfDeclared {
        private String own;
    }

    @uk.gov.hmcts.ccd.sdk.api.CCD(member = "absent", label = "Nope")
    private static class NoSuchMember {
        private String present;
    }
}
