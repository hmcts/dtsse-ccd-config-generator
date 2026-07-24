package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * A delegating no-arg getter the retrofit patch synthesises on the team's root case-data class so a
 * {@code grantComplexType} can reference the complex field by a real {@code CaseData::getX} method
 * reference.
 *
 * <p>An {@code AuthorisationComplexType} grant restricts a complex CaseField (its
 * {@link ComplexTypeAuthModel#getCaseFieldId()}). In retrofit mode the granted field is frequently
 * reachable only through a {@code @JsonUnwrapped} member of the root class (fpl's
 * {@code placement}/{@code courtBundleListV2}/{@code hiddenApplicationsBundle} live on
 * {@code PlacementEventData}/{@code HearingDocuments}/{@code RemovalToolData}), so the flat CCD field
 * id has no direct getter on {@code CaseData}. The SDK's {@code grantComplexType} resolves the getter
 * by introspecting a serialized lambda, which needs a REAL {@code Type::method} reference — a
 * multi-hop lambda ({@code caseData -> caseData.getX().getY()}) fails at generation
 * ({@code PropertyUtils.resolveGetterMethod} cannot resolve a synthetic lambda method). So the patch
 * adds a delegating getter {@code getFieldId()} that returns {@code getParent()[.getHop()]*.getMember()}
 * (mirroring how fpl's own {@code getOrderCollection()} delegates), and the config emits
 * {@code CaseData::getFieldId}. The getter is {@code @JsonIgnore} so it adds no Jackson property, and
 * the SDK discovers CaseFields from the model's <em>fields</em> (not getters), so it produces no
 * spurious CaseField.
 */
@Value
@Builder
public class ComplexTypeAuthGetter {

  /**
   * The complex CaseField id the grant restricts, e.g. {@code placement}.
   */
  String caseFieldId;

  /**
   * The delegating getter's method name, {@code get} + PascalCase({@link #caseFieldId}). Decapitalises
   * back to the CCD field id via the SDK's {@code PropertyUtils.derivePropertyName}, so the generated
   * {@code AuthorisationComplexType} row carries the correct {@code CaseFieldID}.
   */
  String getterName;

  /**
   * The delegating getter's declared return type, as a source string. Normally {@code Object}: the
   * SDK never invokes the getter (it only introspects the lambda's method name), and the model
   * member's real return type (an {@code Element}-based collection) differs from the linker's
   * definition-inferred {@code ListValue}-based one, so a concrete declared type would not compile.
   * {@code Object} is always assignable from the delegated value and always compiles.
   */
  String returnTypeSource;

  /**
   * The getter names to invoke, outermost-first, to reach the member from {@code CaseData}: e.g.
   * {@code [getPlacementEventData, getPlacement]} renders {@code getPlacementEventData().getPlacement()}.
   */
  List<String> delegationChain;
}
