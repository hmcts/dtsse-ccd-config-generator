package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.diff.NormalisingCcdConfigComparator;

/**
 * Decision-7 spike: proves the ET "one shared base model, one thin subclass per case type" shape
 * works end-to-end through the SDK, WITHOUT building full ET support. A fake base {@code EtCaseData}
 * (shared fields) plus two subclasses ({@code EnglandWalesCaseData}, {@code ScotlandCaseData}) each
 * adding a field, plus two {@code CCDConfig}s (one per case type, each bound to its subclass), are
 * compiled and run through the generator. The assertions are:
 *
 * <ul>
 *   <li>each case type's generated {@code CaseField} set = base fields ∪ that subclass's fields
 *       (the SDK's {@code ReflectionUtils.doWithFields} walks the superclass);</li>
 *   <li>the shared complex type ({@code Party}) and shared enum-backed field resolve identically
 *       through both subclasses (same ComplexTypes rows, same FixedList).</li>
 * </ul>
 *
 * <p>Outcome recorded in {@code docs/retrofit-existing-models-proposal.md} under decision 7.
 */
@Tag("round-trip")
class EtSubclassSpikeTest {

  private static final String MODEL_PKG = "uk.gov.hmcts.etspike.model";
  private static final String CONFIG_PKG = "uk.gov.hmcts.etspike.config";

  @Test
  void sharedBaseWithPerCaseTypeSubclassesResolvesCorrectly(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    writeSources(src);

    Path classesOut = work.resolve("classes");
    Path defOut = work.resolve("definition");
    ClassLoader generated = GeneratedSourceCompiler.compile(src, classesOut);
    GeneratorRunner.generate(generated, defOut, "uk.gov.hmcts.ccd.sdk", CONFIG_PKG, MODEL_PKG);

    Set<String> ew = caseFieldIds(defOut, "ET_EnglandWales");
    Set<String> scotland = caseFieldIds(defOut, "ET_Scotland");

    // Base fields present in both; each subclass field only in its own case type.
    assertThat(ew).contains("claimantName", "claimType", "respondent", "englandWalesOffice");
    assertThat(ew).doesNotContain("scotlandOffice");
    assertThat(scotland).contains("claimantName", "claimType", "respondent", "scotlandOffice");
    assertThat(scotland).doesNotContain("englandWalesOffice");

    // base ∪ subclass, exactly.
    assertThat(ew).isEqualTo(Set.of(
        "claimantName", "claimType", "respondent", "englandWalesOffice"));
    assertThat(scotland).isEqualTo(Set.of(
        "claimantName", "claimType", "respondent", "scotlandOffice"));

    // Shared complex type resolves identically through both subclasses.
    assertThat(complexTypeIds(defOut, "ET_EnglandWales"))
        .isEqualTo(complexTypeIds(defOut, "ET_Scotland"))
        .contains("Party");
    // Shared enum-backed FixedList resolves identically.
    assertThat(fixedListIds(defOut, "ET_EnglandWales"))
        .isEqualTo(fixedListIds(defOut, "ET_Scotland"))
        .contains("EtClaimType");
  }

  private Set<String> caseFieldIds(Path defOut, String caseType) {
    return rowIds(defOut, caseType, "CaseField", "ID");
  }

  private Set<String> complexTypeIds(Path defOut, String caseType) {
    return rowIds(defOut, caseType, "ComplexTypes", "ID");
  }

  private Set<String> fixedListIds(Path defOut, String caseType) {
    return rowIds(defOut, caseType, "FixedLists", "ID");
  }

  private Set<String> rowIds(Path defOut, String caseType, String sheet, String idColumn) {
    Map<String, List<Map<String, Object>>> aggregated =
        NormalisingCcdConfigComparator.aggregateDirectory(defOut.resolve(caseType).toFile());
    List<Map<String, Object>> rows = aggregated.getOrDefault(sheet, List.of());
    return rows.stream()
        .map(r -> String.valueOf(r.get(idColumn)))
        .filter(id -> id != null && !"null".equals(id) && !"caseHistory".equals(id))
        .collect(Collectors.toSet());
  }

  private void writeSources(Path src) throws Exception {
    write(src, MODEL_PKG, "EtClaimType.java", """
        package %s;
        import lombok.Getter;
        import lombok.RequiredArgsConstructor;
        import uk.gov.hmcts.ccd.sdk.api.HasLabel;
        @Getter @RequiredArgsConstructor
        public enum EtClaimType implements HasLabel {
          UNFAIR_DISMISSAL("Unfair dismissal"), DISCRIMINATION("Discrimination");
          private final String label;
        }
        """.formatted(MODEL_PKG));

    write(src, MODEL_PKG, "Party.java", """
        package %s;
        import lombok.Data;
        import uk.gov.hmcts.ccd.sdk.api.CCD;
        import uk.gov.hmcts.ccd.sdk.api.ComplexType;
        @Data @ComplexType(generate = true)
        public class Party {
          @CCD(label = "Full name") private String fullName;
        }
        """.formatted(MODEL_PKG));

    write(src, MODEL_PKG, "EtState.java", """
        package %s;
        public enum EtState { Accepted, Closed }
        """.formatted(MODEL_PKG));

    write(src, MODEL_PKG, "EtUserRole.java", """
        package %s;
        import uk.gov.hmcts.ccd.sdk.api.HasRole;
        public enum EtUserRole implements HasRole {
          CASEWORKER("caseworker-et", "CRUD");
          private final String role; private final String perms;
          EtUserRole(String role, String perms) { this.role = role; this.perms = perms; }
          public String getRole() { return role; }
          public String getCaseTypePermissions() { return perms; }
        }
        """.formatted(MODEL_PKG));

    // Shared base: the ~common fields both case types declare.
    write(src, MODEL_PKG, "EtCaseData.java", """
        package %s;
        import lombok.Data;
        import uk.gov.hmcts.ccd.sdk.api.CCD;
        import uk.gov.hmcts.ccd.sdk.type.FieldType;
        @Data
        public class EtCaseData {
          @CCD(label = "Claimant name") private String claimantName;
          @CCD(label = "Type of claim", typeOverride = FieldType.FixedList,
               typeParameterOverride = "EtClaimType") private EtClaimType claimType;
          @CCD(label = "Respondent") private Party respondent;
        }
        """.formatted(MODEL_PKG));

    // Thin subclass per case type, each adding one case-type-specific field.
    write(src, MODEL_PKG, "EnglandWalesCaseData.java", """
        package %s;
        import lombok.Data;
        import lombok.EqualsAndHashCode;
        import uk.gov.hmcts.ccd.sdk.api.CCD;
        @Data @EqualsAndHashCode(callSuper = true)
        public class EnglandWalesCaseData extends EtCaseData {
          @CCD(label = "England/Wales office") private String englandWalesOffice;
        }
        """.formatted(MODEL_PKG));

    write(src, MODEL_PKG, "ScotlandCaseData.java", """
        package %s;
        import lombok.Data;
        import lombok.EqualsAndHashCode;
        import uk.gov.hmcts.ccd.sdk.api.CCD;
        @Data @EqualsAndHashCode(callSuper = true)
        public class ScotlandCaseData extends EtCaseData {
          @CCD(label = "Scotland office") private String scotlandOffice;
        }
        """.formatted(MODEL_PKG));

    write(src, CONFIG_PKG, "EnglandWalesConfig.java", configSource(
        "EnglandWalesConfig", "EnglandWalesCaseData", "ET_EnglandWales"));
    write(src, CONFIG_PKG, "ScotlandConfig.java", configSource(
        "ScotlandConfig", "ScotlandCaseData", "ET_Scotland"));

    // A @SpringBootApplication so the generator context autoconfigures the ObjectMapper the SDK's
    // runtime beans need (matching what the converter's ApplicationEmitter emits for real runs).
    write(src, CONFIG_PKG, "SpikeApplication.java", """
        package %s;
        import org.springframework.boot.autoconfigure.SpringBootApplication;
        @SpringBootApplication(scanBasePackages = {"uk.gov.hmcts.ccd.sdk", "%s", "%s"})
        public class SpikeApplication { }
        """.formatted(CONFIG_PKG, CONFIG_PKG, MODEL_PKG));
  }

  private String configSource(String className, String caseDataClass, String caseTypeId) {
    return """
        package %s;
        import org.springframework.stereotype.Component;
        import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
        import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
        import %s.%s;
        import %s.EtState;
        import %s.EtUserRole;
        @Component
        public class %s implements CCDConfig<%s, EtState, EtUserRole> {
          public void configure(ConfigBuilder<%s, EtState, EtUserRole> builder) {
            builder.caseType("%s", "%s", "ET case type");
            builder.jurisdiction("EMPLOYMENT", "Employment", "Employment Tribunals");
            builder.grant(EtState.Accepted, java.util.Set.of(
                uk.gov.hmcts.ccd.sdk.api.Permission.R), EtUserRole.CASEWORKER);
            builder.event("create")
                .initialState(EtState.Accepted)
                .name("Create")
                .grant(java.util.Set.of(uk.gov.hmcts.ccd.sdk.api.Permission.C,
                    uk.gov.hmcts.ccd.sdk.api.Permission.R,
                    uk.gov.hmcts.ccd.sdk.api.Permission.U,
                    uk.gov.hmcts.ccd.sdk.api.Permission.D), EtUserRole.CASEWORKER)
                .fields()
                .optional(%s::getClaimantName)
                .optional(%s::getClaimType)
                .complex(%s::getRespondent).done();
          }
        }
        """.formatted(
        CONFIG_PKG, MODEL_PKG, caseDataClass, MODEL_PKG, MODEL_PKG,
        className, caseDataClass, caseDataClass, caseTypeId, caseTypeId,
        caseDataClass, caseDataClass, caseDataClass);
  }

  private void write(Path src, String pkg, String fileName, String content) throws Exception {
    Path dir = src.resolve(pkg.replace('.', File.separatorChar));
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(fileName), content);
  }
}
