package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.Map;

/**
 * The complex-type IDs the SDK already ships as classes under
 * {@code uk.gov.hmcts.ccd.sdk.type}, keyed by their CCD complex-type ID.
 *
 * <p>These are declared with {@code @ComplexType(name = ..., generate = false)} in the SDK,
 * so the definition store already knows the type and the generator does not emit a
 * ComplexTypes file for it. When a definition's ComplexTypes sheet declares one of these IDs
 * the linker skips generating a class and points referencing fields at the predefined type
 * instead.
 */
final class SdkPredefinedTypes {

  private static final String PKG = "uk.gov.hmcts.ccd.sdk.type.";

  private static final Map<String, String> BY_ID = Map.ofEntries(
      Map.entry("Address", PKG + "Address"),
      Map.entry("AddressUK", PKG + "AddressUK"),
      Map.entry("AddressGlobal", PKG + "AddressGlobal"),
      Map.entry("AddressGlobalUK", PKG + "AddressGlobalUK"),
      Map.entry("CaseLink", PKG + "CaseLink"),
      Map.entry("CaseLocation", PKG + "CaseLocation"),
      Map.entry("ChangeOrganisationRequest", PKG + "ChangeOrganisationRequest"),
      Map.entry("Document", PKG + "Document"),
      Map.entry("Fee", PKG + "Fee"),
      Map.entry("FlagDetail", PKG + "FlagDetail"),
      Map.entry("Flags", PKG + "Flags"),
      Map.entry("OrderSummary", PKG + "OrderSummary"),
      Map.entry("Organisation", PKG + "Organisation"),
      Map.entry("OrganisationPolicy", PKG + "OrganisationPolicy"),
      Map.entry("PreviousOrganisation", PKG + "PreviousOrganisation"),
      Map.entry("SearchCriteria", PKG + "SearchCriteria"),
      Map.entry("SearchParty", PKG + "SearchParty"),
      Map.entry("TTL", PKG + "TTL"),
      Map.entry("WaysToPay", PKG + "WaysToPay"),
      Map.entry("YesOrNo", PKG + "YesOrNo"));

  private SdkPredefinedTypes() {
  }

  /**
   * Whether the complex-type ID is one the SDK already ships and should not be generated.
   *
   * @param id the ComplexTypes sheet ID
   * @return true when the SDK provides this type
   */
  static boolean isPredefined(String id) {
    return BY_ID.containsKey(id);
  }

  /**
   * The fully qualified Java type reference for a predefined complex-type ID.
   *
   * @param id the ComplexTypes sheet ID
   * @return the Java FQN, or null when the ID is not predefined
   */
  static String javaTypeFor(String id) {
    return BY_ID.get(id);
  }
}
