package uk.gov.hmcts.example.enums;

/**
 * A model enum whose name shares nothing with the {@code FixedLists} ID describing it: the definition
 * declares {@code handoffReasonFixedList} and the field referencing it is declared as THIS enum
 * (probate's real shape). The FixedLists half of the declaration binding — {@code FixedListGenerator}
 * reads the list ID from the same class-level {@code @ComplexType(name)} the complex-type pin uses,
 * falling back to the enum's simple name, so without the pin the definition's list gets a companion
 * enum nothing references while this enum emits its rows under {@code HandoffReasonId}.
 */
public enum HandoffReasonId {

  INTERPRETER,

  TRUST_CORP
}
