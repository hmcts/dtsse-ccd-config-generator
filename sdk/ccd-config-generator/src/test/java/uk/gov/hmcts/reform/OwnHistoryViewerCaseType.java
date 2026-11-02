package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.ExplicitState.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Declares a {@code CaseHistoryViewer} of its own and places it on a tab, the shape a definition
 * converted from hand-written JSON has. The checked-in sheets show neither the injected
 * {@code caseHistory} field nor the injected {@code CaseHistory} tab, so the viewer the tab places
 * is the one {@code ccd-data-store-api} fills.
 */
@Component
public class OwnHistoryViewerCaseType
    implements CCDConfig<OwnHistoryViewerCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<OwnHistoryViewerCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("OwnHistoryViewer", "OwnHistoryViewer", "Own history viewer case type");

    builder.event("edit")
        .forState(Open)
        .name("Edit")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .optional(OwnHistoryViewerCaseData::getSharedField);

    builder.tab("history", "History")
        .field(OwnHistoryViewerCaseData::getAuditTrail);
  }
}
