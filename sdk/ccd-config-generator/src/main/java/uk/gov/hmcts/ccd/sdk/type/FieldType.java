package uk.gov.hmcts.ccd.sdk.type;

/**
 * CCD base field types accepted by the definition-store importer, plus predefined complex types
 * shipped platform-side (eg. {@link #CaseLink}, {@link #JudicialUser}). Used with
 * {@code @CCD(typeOverride = ...)} to force a field's {@code FieldType} column independently of its
 * Java type.
 */
public enum FieldType {
  Unspecified,
  Email,
  PhoneUK,
  Date,
  DateTime,
  Number,
  Document,
  Schedule,
  TextArea,
  Text,
  FixedList,
  FixedRadioList,
  YesOrNo,
  Address,
  AddressUK,
  AddressGlobal,
  AddressGlobalUK,
  CaseLink,
  CaseLocation,
  OrderSummary,
  MultiSelectList,
  Collection,
  Label,
  CaseHistoryViewer,
  CasePaymentHistoryViewer,
  DynamicList,
  DynamicRadioList,
  DynamicMultiSelectList,
  Flags,
  FlagLauncher,
  FlagType,
  FlagDetail,
  ComponentLauncher,
  SearchCriteria,
  TTL,
  MoneyGBP,
  Fee,
  Organisation,
  OrganisationPolicy,
  ChangeOrganisationRequest,
  WaysToPay,
  JudicialUser,
  CaseQueriesCollection
}
