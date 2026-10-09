package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.EventColumnsState.Closed;
import static uk.gov.hmcts.reform.EventColumnsState.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Callbacks pointed at the service's own endpoints by URL. {@code byUrl} sets every hook by URL,
 * with and without retries; {@code byHandler} sets the same hooks with SDK handlers, whose output
 * the URL overloads must leave alone.
 */
@Component
public class CallbackUrlsCaseType
    implements CCDConfig<CallbackUrlsCaseData, EventColumnsState, UserRole> {

  @Override
  public void configure(ConfigBuilder<CallbackUrlsCaseData, EventColumnsState, UserRole> builder) {
    builder.caseType("CallbackUrls", "Callback URLs", "Callback URLs case type");
    builder.jurisdiction("CALLBACKURLS", "Callback URLs jurisdiction", "Callback URLs jurisdiction desc");

    builder.event("byUrl")
        .forStateTransition(Open, Closed)
        .name("By URL")
        .grant(CRU, LOCAL_AUTHORITY)
        .aboutToStartCallback("${CCD_DEF_IA_URL}/asylum/ccdAboutToStart", 5, 5, 5, 5)
        .aboutToSubmitCallback("${CCD_DEF_IA_URL}/asylum/ccdAboutToSubmit", 5, 5, 5, 5)
        .submittedCallback("${CCD_DEF_IA_URL}/asylum/ccdSubmitted")
        .fields()
        .page("withRetries", "${CCD_DEF_IA_URL}/asylum/ccdMidEvent?pageId=withRetries", 3, 3)
        .optional(CallbackUrlsCaseData::getFirst)
        .optional(CallbackUrlsCaseData::getSecond)
        .page("withoutRetries", "${CCD_DEF_IA_URL}/asylum/ccdMidEvent?pageId=withoutRetries")
        .optional(CallbackUrlsCaseData::getThird);

    builder.event("byHandler")
        .forStateTransition(Open, Closed)
        .name("By handler")
        .grant(CRU, LOCAL_AUTHORITY)
        .aboutToStartCallback((details) -> response())
        .aboutToSubmitCallback((details, before) -> response())
        .fields()
        .page("handled", (details, before) -> response())
        .optional(CallbackUrlsCaseData::getFirst)
        .page("plain")
        .optional(CallbackUrlsCaseData::getSecond);
  }

  private static AboutToStartOrSubmitResponse<CallbackUrlsCaseData, EventColumnsState> response() {
    return AboutToStartOrSubmitResponse.<CallbackUrlsCaseData, EventColumnsState>builder().build();
  }
}
