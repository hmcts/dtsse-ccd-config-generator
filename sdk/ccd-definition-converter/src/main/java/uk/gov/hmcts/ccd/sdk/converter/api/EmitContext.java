package uk.gov.hmcts.ccd.sdk.converter.api;

import com.palantir.javapoet.ClassName;
import lombok.Builder;
import lombok.Value;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/** Everything emitters need beyond the model itself. */
@Value
@Builder
public class EmitContext {

  ConversionOptions options;
  GapCollector gaps;

  public String modelPackage() {
    return options.getModelPackage();
  }

  public String configPackage() {
    return options.getConfigPackage();
  }

  /**
   * The package the per-event {@code CCDConfig} classes (and their page classes) are emitted into:
   * {@code <configPackage>.event}, mirroring the reference services' {@code <root>.event} idiom
   * (nfdiv/sptribs). Being a sub-package of the (component-scanned) root config package, its beans
   * are still discovered by the SDK generator.
   *
   * @return the event package
   */
  public String eventPackage() {
    return configPackage() + ".event";
  }

  /**
   * The package the per-wizard-page classes are emitted into: {@code <configPackage>.event.page},
   * mirroring the reference services' {@code <root>.event.page} idiom. Page classes are plain
   * static helpers referenced by their event class, not beans, so scanning is irrelevant to them.
   *
   * @return the page package
   */
  public String pagePackage() {
    return eventPackage() + ".page";
  }

  /**
   * The package the {@code HasAccessControl} access classes are emitted into:
   * {@code <configPackage>.access}, mirroring the reference services' {@code <root>.access} /
   * {@code <model>.access} idiom. The {@code @CCD(access = {…})} references on the model point here.
   *
   * @return the access package
   */
  public String accessPackage() {
    return accessPackage(configPackage());
  }

  /**
   * The access-class package for a given root config package — the single source of truth both the
   * companion emitter ({@link #accessPackage()} → {@code AccessClassEmitter}) and the retrofit patch
   * emitter (which imports the {@code @CCD(access = {…})} classes) resolve through, so the patch's
   * imports and the emitted files can never land in different packages (the {@code ccd.config} vs
   * {@code ccd.access} split that broke the prl retrofit when the two derivations drifted apart).
   *
   * @param configPackage the root config package
   * @return the access package {@code <configPackage>.access}
   */
  public static String accessPackage(String configPackage) {
    return configPackage + ".access";
  }

  /**
   * The {@code CaseData}-typed class the generated config binds its typed getters to. In generate
   * mode this is the freshly generated {@code <modelPackage>.CaseData}; in retrofit mode it is the
   * team's own root model class ({@link ConversionOptions#getRetrofitCaseDataClass()}), so the
   * emitted {@code CCDConfig<TeamCaseData, …>} references the model the team's callbacks already use.
   *
   * @return the CaseData class name
   */
  public ClassName caseDataClass() {
    String simple = options.getRetrofitCaseDataClass() != null
        ? options.getRetrofitCaseDataClass() : "CaseData";
    return ClassName.get(modelPackage(), simple);
  }

  /**
   * The {@code State} enum the config binds to: the team's reusable enum when
   * {@link ConversionOptions#getRetrofitStateClass()} is set (all state IDs resolve — proposal
   * decision 3), otherwise the freshly generated {@code <modelPackage>.State}.
   *
   * @return the State enum class name
   */
  public ClassName stateClass() {
    if (options.getRetrofitStateClass() != null) {
      String pkg = options.getRetrofitStateClassPackage() != null
          ? options.getRetrofitStateClassPackage() : modelPackage();
      return ClassName.get(pkg, options.getRetrofitStateClass());
    }
    return ClassName.get(modelPackage(), "State");
  }

  /**
   * The {@code UserRole} enum the config binds to. Always the freshly generated
   * {@code <modelPackage>.UserRole} (retrofit generates a fresh UserRole enum — proposal decision 3).
   *
   * @return the UserRole enum class name
   */
  public ClassName userRoleClass() {
    return ClassName.get(modelPackage(), "UserRole");
  }

  /**
   * The {@code State} enum constant name for a CCD state ID. In generate mode the constant name is
   * the state ID verbatim; in retrofit mode a reused enum may name a constant differently from the
   * CCD ID (e.g. {@code PREPARE_FOR_HEARING → CASE_MANAGEMENT}), so this maps back to what the
   * emitted {@code State.<constant>} reference must be.
   *
   * @param stateId the CCD state ID
   * @return the enum constant name to reference
   */
  public String stateConstant(String stateId) {
    java.util.Map<String, String> constants = options.getRetrofitStateConstants();
    if (constants != null && constants.containsKey(stateId)) {
      return constants.get(stateId);
    }
    return stateId;
  }

  /**
   * The provenance javadoc line stamped on a generated type.
   *
   * <p>In <b>generate</b> mode the file is regenerated on every build, so it carries the
   * traditional {@code Generated by ccd-definition-converter … — do not edit by hand.} banner
   * (optionally naming the source sheet it derives from). In <b>retrofit</b> mode the file is an
   * adoption artefact a team is meant to own and edit directly, so the banner is replaced with a
   * one-line provenance note that records where it came from without telling the team not to touch
   * it (finding #10).
   *
   * @param definition the case type / definition the artefact was generated from, named in the
   *     retrofit provenance note; may be null (falls back to a generic phrase)
   * @param sheetClause the source sheet the generate-mode banner names (e.g. {@code "ComplexTypes"}),
   *     or null/empty for the bare banner
   * @return the javadoc text (no trailing newline)
   */
  public String banner(String definition, String sheetClause) {
    if (options.isRetrofit()) {
      String from = definition != null && !definition.isEmpty() ? definition : "the CCD definition";
      return "Generated by ccd-definition-converter from " + from
          + " on migration; owned by this service team.";
    }
    String from = sheetClause == null || sheetClause.isEmpty() ? "" : " from " + sheetClause;
    return "Generated by ccd-definition-converter" + from + " — do not edit by hand.";
  }
}
