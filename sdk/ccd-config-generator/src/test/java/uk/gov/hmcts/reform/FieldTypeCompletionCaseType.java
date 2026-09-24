package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.FieldTypeCompletionState.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Golden test for the {@code WaysToPay}/{@code JudicialUser}/{@code CaseQueriesCollection}
 * additions to {@link uk.gov.hmcts.ccd.sdk.type.FieldType} and their predefined complex types
 * (see {@link FieldTypeCompletionCaseData}).
 */
@Component
public class FieldTypeCompletionCaseType
    implements CCDConfig<FieldTypeCompletionCaseData, FieldTypeCompletionState, UserRole> {

  @Override
  public void configure(ConfigBuilder<FieldTypeCompletionCaseData, FieldTypeCompletionState, UserRole> builder) {
    builder.caseType("FieldTypeCompletion", "Field type completion", "Field type completion case type");

    builder.event("create")
        .initialState(Open)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(FieldTypeCompletionCaseData::getServiceRequest)
        .optional(FieldTypeCompletionCaseData::getAllocatedJudge)
        .optional(FieldTypeCompletionCaseData::getQueries);
  }
}
