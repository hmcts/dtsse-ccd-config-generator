package uk.gov.hmcts.ccd.sdk.converter.ir;

/**
 * The CCD definition column vocabulary, transcribed from ccd-definition-store-api
 * {@code excel-importer/.../util/mapper/ColumnName.java}.
 *
 * <p>Keys in definition JSON rows are these column names verbatim. {@link #ACCESS_PROFILE}
 * has the legacy alias {@link #USER_ROLE}; both spellings appear in real definitions and
 * are accepted by the definition store importer.
 */
public final class Columns {

  public static final String ACCESS_PROFILE = "AccessProfile";
  public static final String USER_ROLE = "UserRole";
  /**
   * Array shorthand naming many roles in one authorisation row; expanded to per-role rows.
   */
  public static final String USER_ROLES = "UserRoles";
  /**
   * Array shorthand of {@code {UserRoles, CRUD}} grants in one row; expanded to per-role rows.
   */
  public static final String ACCESS_CONTROL = "AccessControl";
  /** A CaseRole's jurisdiction (always the case type's); the SDK's CaseRoleGenerator omits it. */
  public static final String JURISDICTION_ID = "JurisdictionID";
  public static final String CALLBACK_URL_ABOUT_TO_START_EVENT = "CallBackURLAboutToStartEvent";
  public static final String CALLBACK_URL_ABOUT_TO_SUBMIT_EVENT = "CallBackURLAboutToSubmitEvent";
  public static final String CALLBACK_URL_SUBMITTED_EVENT = "CallBackURLSubmittedEvent";
  public static final String CALLBACK_URL_MID_EVENT = "CallBackURLMidEvent";
  public static final String CALLBACK_GET_CASE_URL = "CallbackGetCaseUrl";
  public static final String CAN_SAVE_DRAFT = "CanSaveDraft";
  public static final String CASE_EVENT_ID = "CaseEventID";
  public static final String CASE_EVENT_FIELD_LABEL = "CaseEventFieldLabel";
  public static final String CASE_EVENT_FIELD_HINT = "CaseEventFieldHint";
  public static final String CASE_FIELD_ID = "CaseFieldID";
  public static final String CASE_TYPE_ID = "CaseTypeID";
  /**
   * Case-insensitive legacy spelling of {@link #CASE_TYPE_ID}; the importer treats them alike.
   */
  public static final String CASE_TYPE_ID_LOWER = "CaseTypeId";
  public static final String CHANNEL = "Channel";
  public static final String CRUD = "CRUD";
  public static final String DEFAULT_HIDDEN = "DefaultHidden";
  public static final String DESCRIPTION = "Description";
  public static final String DISPLAY_ORDER = "DisplayOrder";
  public static final String FIELD_DISPLAY_ORDER = "FieldDisplayOrder";
  public static final String DISPLAY_CONTEXT = "DisplayContext";
  public static final String DISPLAY_CONTEXT_PARAMETER = "DisplayContextParameter";
  public static final String ELEMENT_LABEL = "ElementLabel";
  public static final String EVENT_ELEMENT_LABEL = "EventElementLabel";
  public static final String END_BUTTON_LABEL = "EndButtonLabel";
  public static final String FIELD_TYPE = "FieldType";
  public static final String FIELD_TYPE_PARAMETER = "FieldTypeParameter";
  public static final String HINT_TEXT = "HintText";
  public static final String EVENT_HINT_TEXT = "EventHintText";
  public static final String ID = "ID";
  public static final String LABEL = "Label";
  public static final String LIST_ELEMENT_CODE = "ListElementCode";
  public static final String LIST_ELEMENT = "ListElement";
  public static final String LIVE_FROM = "LiveFrom";
  public static final String LIVE_TO = "LiveTo";
  public static final String MAX = "Max";
  public static final String MIN = "Min";
  public static final String NAME = "Name";
  public static final String PAGE_ID = "PageID";
  public static final String PAGE_LABEL = "PageLabel";
  public static final String PAGE_DISPLAY_ORDER = "PageDisplayOrder";
  public static final String PAGE_FIELD_DISPLAY_ORDER = "PageFieldDisplayOrder";
  public static final String PAGE_COLUMN_NUMBER = "PageColumnNumber";
  public static final String POST_CONDITION_STATE = "PostConditionState";
  public static final String PRE_CONDITION_STATES = "PreConditionState(s)";
  public static final String PRINTABLE_DOCUMENTS_URL = "PrintableDocumentsUrl";
  public static final String REGULAR_EXPRESSION = "RegularExpression";
  public static final String RETRIES_TIMEOUT_ABOUT_TO_START_EVENT = "RetriesTimeoutAboutToStartEvent";
  public static final String RETRIES_TIMEOUT_URL_ABOUT_TO_START_EVENT =
      "RetriesTimeoutURLAboutToStartEvent";
  public static final String RETRIES_TIMEOUT_URL_ABOUT_TO_SUBMIT_EVENT = "RetriesTimeoutURLAboutToSubmitEvent";
  public static final String RETRIES_TIMEOUT_URL_SUBMITTED_EVENT = "RetriesTimeoutURLSubmittedEvent";
  public static final String RETRIES_TIMEOUT_URL_MID_EVENT = "RetriesTimeoutURLMidEvent";
  public static final String RETRIES_GET_CASE_URL = "RetriesGetCaseUrl";
  public static final String SEARCHABLE = "Searchable";
  public static final String SECURITY_CLASSIFICATION = "SecurityClassification";
  public static final String FIELD_SHOW_CONDITION = "FieldShowCondition";
  public static final String PAGE_SHOW_CONDITION = "PageShowCondition";
  public static final String TAB_SHOW_CONDITION = "TabShowCondition";
  public static final String SEARCH_ALIAS_ID = "SearchAliasID";
  public static final String SHUTTERED = "Shuttered";
  public static final String ENABLE_FOR_DELETION = "EnableForDeletion";
  public static final String SIGNIFICANT_EVENT = "SignificantEvent";
  public static final String SHOW_SUMMARY = "ShowSummary";
  public static final String PUBLISH = "Publish";
  public static final String PUBLISH_AS = "PublishAs";
  public static final String SHOW_EVENT_NOTES = "ShowEventNotes";
  public static final String SHOW_SUMMARY_CHANGE_OPTION = "ShowSummaryChangeOption";
  public static final String SHOW_SUMMARY_CONTENT_OPTION = "ShowSummaryContentOption";
  public static final String CASE_STATE_ID = "CaseStateID";
  public static final String TAB_ID = "TabID";
  public static final String TAB_LABEL = "TabLabel";
  public static final String TAB_DISPLAY_ORDER = "TabDisplayOrder";
  public static final String TAB_FIELD_DISPLAY_ORDER = "TabFieldDisplayOrder";
  public static final String TITLE_DISPLAY = "TitleDisplay";
  public static final String TTL_INCREMENT = "TTLIncrement";
  public static final String USER_IDAM_ID = "UserIDAMId";
  public static final String USE_CASE = "UseCase";
  public static final String RESULTS_ORDERING = "ResultsOrdering";
  public static final String WORK_BASKET_DEFAULT_JURISDICTION = "WorkBasketDefaultJurisdiction";
  public static final String WORK_BASKET_DEFAULT_CASETYPE = "WorkBasketDefaultCaseType";
  public static final String WORK_BASKET_DEFAULT_STATE = "WorkBasketDefaultState";
  public static final String BANNER_ENABLED = "BannerEnabled";
  public static final String BANNER_DESCRIPTION = "BannerDescription";
  public static final String BANNER_URL_TEXT = "BannerURLText";
  public static final String BANNER_URL = "BannerURL";
  public static final String DEFAULT_VALUE = "DefaultValue";
  public static final String RETAIN_HIDDEN_VALUE = "RetainHiddenValue";
  public static final String REASON_REQUIRED = "ReasonRequired";
  public static final String NOC_ACTION_INTERPRETATION_REQUIRED = "NoCActionInterpretationRequired";
  public static final String QUESTION_TEXT = "QuestionText";
  public static final String ANSWER_FIELD_TYPE = "AnswerFieldType";
  public static final String CASE_ROLE_ID = "CaseRoleId";
  public static final String QUESTION_ID = "QuestionId";
  public static final String IGNORE_NULL_FIELDS = "IgnoreNULLFields";
  public static final String ANSWER = "Answer";
  public static final String ROLE_NAME = "RoleName";
  public static final String AUTHORISATION = "Authorisation";
  public static final String READ_ONLY = "ReadOnly";
  public static final String DISABLED = "Disabled";
  public static final String ACCESS_PROFILES = "AccessProfiles";
  public static final String SEARCH_PARTY_NAME = "SearchPartyName";
  public static final String SEARCH_PARTY_EMAIL_ADDRESS = "SearchPartyEmailAddress";
  public static final String SEARCH_PARTY_ADDRESS_LINE_1 = "SearchPartyAddressLine1";
  public static final String SEARCH_PARTY_POST_CODE = "SearchPartyPostCode";
  public static final String SEARCH_PARTY_DOB = "SearchPartyDOB";
  public static final String SEARCH_PARTY_DOD = "SearchPartyDOD";
  public static final String SEARCH_PARTY_COLLECTION_FIELD_NAME = "SearchPartyCollectionFieldName";
  public static final String OTHER_CASE_REFERENCE = "OtherCaseReference";
  public static final String EVENT_ENABLING_CONDITION = "EventEnablingCondition";
  public static final String CATEGORY_ID = "CategoryID";
  public static final String CATEGORY_LABEL = "CategoryLabel";
  public static final String PARENT_CATEGORY_ID = "ParentCategoryID";
  public static final String CASE_ACCESS_CATEGORIES = "CaseAccessCategories";
  public static final String ACCESS_TYPE_ID = "AccessTypeID";
  public static final String ORGANISATION_PROFILE_ID = "OrganisationProfileID";
  public static final String ACCESS_MANDATORY = "AccessMandatory";
  public static final String ACCESS_DEFAULT = "AccessDefault";
  public static final String DISPLAY = "Display";
  public static final String ORGANISATIONAL_ROLE_NAME = "OrganisationalRoleName";
  public static final String GROUP_ROLE_NAME = "GroupRoleName";
  public static final String CASE_ASSIGNED_ROLE_FIELD = "CaseAssignedRoleField";
  public static final String GROUP_ACCESS_ENABLED = "GroupAccessEnabled";
  public static final String CASE_ACCESS_GROUP_ID_TEMPLATE = "CaseAccessGroupIDTemplate";
  public static final String NULLIFY_BY_DEFAULT = "NullifyByDefault";

  private Columns() {
  }
}
