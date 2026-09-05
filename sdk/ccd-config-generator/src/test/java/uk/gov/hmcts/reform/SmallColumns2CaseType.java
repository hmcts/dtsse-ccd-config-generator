package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.SmallColumns2State.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Exercises four small, independently-optional column grafts:
 * {@link ConfigBuilder#printableDocumentsUrl(String)} (CaseType {@code PrintableDocumentsUrl}),
 * {@link uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder#canSaveDraft()} (CaseEvent
 * {@code CanSaveDraft}), and per-field
 * {@link uk.gov.hmcts.ccd.sdk.api.FieldCollection.FieldCollectionBuilder#showSummaryContentOption(int)}
 * / {@link uk.gov.hmcts.ccd.sdk.api.FieldCollection.FieldCollectionBuilder#nullifyByDefault()}
 * (CaseEventToFields {@code ShowSummaryContentOption} / {@code NullifyByDefault}). Each is
 * default-off; a config that does not call them produces no trace of the corresponding column.
 */
@Component
public class SmallColumns2CaseType
    implements CCDConfig<SmallColumns2CaseData, SmallColumns2State, UserRole> {

  @Override
  public void configure(ConfigBuilder<SmallColumns2CaseData, SmallColumns2State, UserRole> builder) {
    builder.caseType("SmallColumns2", "Small columns 2", "Small columns 2 case type");
    builder.jurisdiction("SMALLCOLUMNS2", "Small columns 2 jurisdiction", "Small columns 2 jurisdiction desc");
    builder.printableDocumentsUrl("http://localhost:4013/documents/print");

    builder.event("create")
        .initialState(Open)
        .name("Create")
        .canSaveDraft()
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(SmallColumns2CaseData::getAField)
        .showSummaryContentOption(1)
        .nullifyByDefault();
  }
}
