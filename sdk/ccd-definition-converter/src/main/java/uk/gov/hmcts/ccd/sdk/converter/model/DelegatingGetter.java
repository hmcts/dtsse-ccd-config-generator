package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * A delegating no-arg getter the retrofit patch synthesises on the team's root case-data class so a
 * builder call needing a real {@code CaseData::getX} method reference can address a flat CCD field
 * the team's model only reaches through a {@code @JsonUnwrapped} member.
 *
 * <p>Two builder APIs take a typed getter for a flat field id and so need this:
 * <ul>
 *   <li>{@code grantComplexType} — an {@code AuthorisationComplexType} grant restricts a complex
 *       CaseField (its {@link ComplexTypeAuthModel#getCaseFieldId()}); fpl's
 *       {@code placement}/{@code courtBundleListV2}/{@code hiddenApplicationsBundle} live on
 *       {@code PlacementEventData}/{@code HearingDocuments}/{@code RemovalToolData}.</li>
 *   <li>{@code Tab.TabBuilder.field(getter, showCondition, displayContext)} — the only tab overload
 *       carrying a {@code DisplayContextParameter}; prl's {@code restrictedDocuments} /
 *       {@code confidentialDocuments} live on {@code ReviewDocuments}, {@code messages} on
 *       {@code SendOrReplyMessage}, {@code confidentialCheckFailed} on
 *       {@code ServiceOfApplication}.</li>
 * </ul>
 *
 * <p>In both cases the flat CCD field id has no direct getter on {@code CaseData}. The SDK resolves
 * the getter by introspecting a serialized lambda, which needs a REAL {@code Type::method} reference —
 * a multi-hop lambda ({@code caseData -> caseData.getX().getY()}) fails at generation
 * ({@code PropertyUtils.resolveGetterMethod} cannot resolve a synthetic lambda method). So the patch
 * adds a delegating getter {@code getFieldId()} that returns {@code getParent()[.getHop()]*.getMember()}
 * (mirroring how fpl's own {@code getOrderCollection()} delegates), and the config emits
 * {@code CaseData::getFieldId}. The getter is {@code @JsonIgnore} so it adds no Jackson property, and
 * the SDK discovers CaseFields from the model's <em>fields</em> (not getters), so it produces no
 * spurious CaseField.
 */
@Value
@Builder
public class DelegatingGetter {

  /**
   * The flat CaseField id the getter stands in for, e.g. {@code placement}.
   */
  String caseFieldId;

  /**
   * The delegating getter's method name, {@code get} + PascalCase({@link #caseFieldId}). Decapitalises
   * back to the CCD field id via the SDK's {@code PropertyUtils.derivePropertyName}, so the generated
   * row ({@code AuthorisationComplexType}'s {@code CaseFieldID}, {@code CaseTypeTab}'s
   * {@code CaseFieldID}) carries the correct id.
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
