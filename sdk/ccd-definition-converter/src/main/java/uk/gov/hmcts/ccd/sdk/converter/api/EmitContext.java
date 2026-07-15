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
}
