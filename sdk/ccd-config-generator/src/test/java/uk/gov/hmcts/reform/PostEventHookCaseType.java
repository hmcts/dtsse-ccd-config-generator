package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.ExplicitState.Open;
import static uk.gov.hmcts.reform.ExplicitState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Registers two post-event hooks. The first replaces the handler's response with a new one, the
 * second appends to whatever it is given, so the response CCD receives shows both their order and
 * that each hook's return value is what is passed on. The {@code refuse} event's submission is
 * rejected by the first hook throwing.
 */
@Component
public class PostEventHookCaseType implements CCDConfig<PostEventHookCaseData, ExplicitState, UserRole> {

  public static final String HOOK_REJECTION = "Rejected by the post-event hook";

  @Override
  public void configure(ConfigBuilder<PostEventHookCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("PostEventHook", "PostEventHook", "Post-event hook case type");

    builder.addPostEventHook(this::firstHook);
    builder.addPostEventHook(this::secondHook);

    builder.event("edit")
        .forStateTransition(Open, Submitted)
        .name("Edit")
        .grant(CRU, HMCTS_ADMIN)
        .aboutToStartCallback(details -> respond(details, "started"))
        .aboutToSubmitCallback((details, before) -> respond(details, "submitted"))
        .fields()
        .page("1", (details, before) -> respond(details, "mid-event"))
        .optional(PostEventHookCaseData::getNote);

    builder.event("refuse")
        .forState(Open)
        .name("Refuse")
        .grant(CRU, HMCTS_ADMIN)
        .aboutToSubmitCallback((details, before) -> respond(details, "submitted"));

    builder.event("handlerErrors")
        .forState(Open)
        .name("Handler errors")
        .grant(CRU, HMCTS_ADMIN)
        .aboutToSubmitCallback((details, before) -> AboutToStartOrSubmitResponse
            .<PostEventHookCaseData, ExplicitState>builder()
            .data(details.getData())
            .errors(List.of("Refused by the handler"))
            .build());
  }

  private AboutToStartOrSubmitResponse<PostEventHookCaseData, ExplicitState> respond(
      CaseDetails<PostEventHookCaseData, ExplicitState> details, String note) {
    details.getData().setNote(note);
    return AboutToStartOrSubmitResponse.<PostEventHookCaseData, ExplicitState>builder()
        .data(details.getData())
        .state(Submitted)
        .build();
  }

  private AboutToStartOrSubmitResponse<PostEventHookCaseData, ExplicitState> firstHook(
      String eventId,
      CaseDetails<PostEventHookCaseData, ExplicitState> details,
      CaseDetails<PostEventHookCaseData, ExplicitState> detailsBefore,
      AboutToStartOrSubmitResponse<PostEventHookCaseData, ExplicitState> response) {
    if ("refuse".equals(eventId)) {
      throw new IllegalStateException(HOOK_REJECTION);
    }
    PostEventHookCaseData data = response.getData();
    data.setHookTrail("first:" + eventId + ":" + data.getNote() + ":" + response.getState());
    return AboutToStartOrSubmitResponse.<PostEventHookCaseData, ExplicitState>builder()
        .data(data)
        .state(response.getState())
        .errors(response.getErrors())
        .warnings(new ArrayList<>(List.of("first")))
        .build();
  }

  private AboutToStartOrSubmitResponse<PostEventHookCaseData, ExplicitState> secondHook(
      String eventId,
      CaseDetails<PostEventHookCaseData, ExplicitState> details,
      CaseDetails<PostEventHookCaseData, ExplicitState> detailsBefore,
      AboutToStartOrSubmitResponse<PostEventHookCaseData, ExplicitState> response) {
    response.getData().setHookTrail(response.getData().getHookTrail() + "|second");
    response.getWarnings().add("second");
    return response;
  }
}
