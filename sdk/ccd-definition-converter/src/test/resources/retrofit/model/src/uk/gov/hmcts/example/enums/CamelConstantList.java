package uk.gov.hmcts.example.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An enum whose constants are spelled exactly as the definition's {@code ListElementCode} — the house
 * style across prl's enums, and unlike the upper-snake constant the converter's own sanitiser would
 * produce. The label pin must match a definition row on the raw code as well as on the sanitised
 * constant name.
 */
public enum CamelConstantList {

  @JsonProperty("nonMolestationOrder")
  nonMolestationOrder,

  @JsonProperty("occupationOrder")
  occupationOrder
}
