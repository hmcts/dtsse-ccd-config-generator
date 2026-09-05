package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.ModelSourceIndex.Type;

/**
 * Reconciles the definition's {@code State} sheet against a candidate {@code State} enum in the
 * team's model, deriving each constant's CCD state ID exactly as the SDK does (proposal decision 3,
 * and {@code StateId.of}): the constant's {@code @JsonProperty} value when present, otherwise its
 * {@code toString()}. When {@code toString()} is overridden (SSCS's {@code @JsonValue} id) the ID
 * cannot be read from source literally; the analyser flags that and — matching the SDK's runtime
 * behaviour — assumes the overridden {@code toString()} returns the enum's declared {@code id}
 * constructor argument, which it recovers from the constant's first string literal argument.
 */
final class StateEnumAnalyser {

  private final ModelSourceIndex index;

  StateEnumAnalyser(ModelSourceIndex index) {
    this.index = index;
  }

  /**
   * Analyses the state reconciliation.
   *
   * @param modelPackage the model package (to prefer a same-package {@code State} enum)
   * @param definitionStateIds the state IDs declared on the definition's State sheet
   * @return the verdict
   */
  RetrofitReport.StateVerdict analyse(String modelPackage, Set<String> definitionStateIds) {
    Optional<Type> stateEnum = findStateEnum(modelPackage);
    if (stateEnum.isEmpty()) {
      return RetrofitReport.StateVerdict.builder()
          .stateEnumFound(false)
          .definitionStates(definitionStateIds.size())
          .summary("No State enum found in the model; the converter generates a fresh State enum "
              + "(constants == state IDs) in retrofit mode.")
          .build();
    }

    Type type = stateEnum.get();
    boolean toStringOverridden = overridesToString(type);
    Set<String> modelStateIds = new LinkedHashSet<>();
    for (EnumConstantDeclaration constant : type.decl.asEnumDeclaration().getEntries()) {
      modelStateIds.add(stateId(constant, toStringOverridden));
    }

    int matched = 0;
    int conflicting = 0;
    List<String> conflicts = new java.util.ArrayList<>();
    for (String defId : definitionStateIds) {
      if (modelStateIds.contains(defId)) {
        matched++;
      } else {
        conflicting++;
        if (conflicts.size() < 20) {
          conflicts.add(defId);
        }
      }
    }

    String summary;
    if (conflicting == 0) {
      summary = "State enum " + type.simpleName + " is directly reusable — all "
          + matched + " definition state IDs match"
          + (toStringOverridden ? " (via an overridden toString()/@JsonValue id)." : ".");
    } else {
      summary = "State enum " + type.simpleName + " conflicts on " + conflicting + " of "
          + definitionStateIds.size() + " definition states; the converter must generate a fresh "
          + "State enum (constants == state IDs) rather than reuse it.";
    }

    RetrofitReport.StateVerdict.StateVerdictBuilder builder = RetrofitReport.StateVerdict.builder()
        .stateEnumFound(true)
        .stateEnumClass(type.fqn)
        .toStringOverridden(toStringOverridden)
        .definitionStates(definitionStateIds.size())
        .matchedStates(matched)
        .conflictingStates(conflicting)
        .summary(summary);
    conflicts.forEach(builder::conflict);
    return builder.build();
  }

  /**
   * The CCD state ID for an enum constant: its {@code @JsonProperty} value if present, else — for a
   * plain enum — its constant name (the SDK's {@code toString()} default). When the enum overrides
   * {@code toString()} (recorded by the caller), the SDK serialises the overridden value, which by
   * convention returns the enum's {@code id} constructor argument; recover that from the constant's
   * first string-literal argument.
   */
  private String stateId(EnumConstantDeclaration constant, boolean toStringOverridden) {
    Optional<String> jsonProperty = Annotations.find(constant, "JsonProperty")
        .flatMap(Annotations::stringValue)
        .filter(v -> !v.isEmpty());
    if (jsonProperty.isPresent()) {
      return jsonProperty.get();
    }
    if (toStringOverridden) {
      Optional<String> firstArg = constant.getArguments().stream()
          .filter(a -> a instanceof StringLiteralExpr)
          .map(a -> ((StringLiteralExpr) a).asString())
          .findFirst();
      if (firstArg.isPresent()) {
        return firstArg.get();
      }
    }
    return constant.getNameAsString();
  }

  /**
   * The map from CCD state ID (as the SDK's {@code StateId} derives it) to the team enum's Java
   * constant name, for a reusable State enum. Retrofit config emission references the constant name
   * (e.g. {@code State.CASE_MANAGEMENT}) while the definition/grants carry the CCD ID (e.g.
   * {@code PREPARE_FOR_HEARING}), so this bridges the two.
   *
   * @param modelPackage the model package (to locate the enum)
   * @return CCD state ID → Java constant name, empty when no State enum is found
   */
  java.util.Map<String, String> stateIdToConstant(String modelPackage) {
    java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
    Optional<Type> stateEnum = findStateEnum(modelPackage);
    if (stateEnum.isEmpty()) {
      return map;
    }
    Type type = stateEnum.get();
    boolean toStringOverridden = overridesToString(type);
    for (EnumConstantDeclaration constant : type.decl.asEnumDeclaration().getEntries()) {
      map.put(stateId(constant, toStringOverridden), constant.getNameAsString());
    }
    return map;
  }

  private boolean overridesToString(Type type) {
    return type.decl.findAll(MethodDeclaration.class).stream()
        .anyMatch(m -> "toString".equals(m.getNameAsString()) && m.getParameters().isEmpty());
  }

  private Optional<Type> findStateEnum(String modelPackage) {
    // Prefer an enum literally named State (FPL/SSCS), then CaseState (Civil), preferring the model
    // package on ambiguity.
    Optional<Type> state = index.bySimpleName("State", modelPackage).filter(Type::isEnum);
    if (state.isPresent()) {
      return state;
    }
    return index.bySimpleName("CaseState", modelPackage).filter(Type::isEnum);
  }
}
