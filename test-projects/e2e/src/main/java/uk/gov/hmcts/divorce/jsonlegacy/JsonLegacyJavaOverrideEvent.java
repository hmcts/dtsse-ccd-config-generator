package uk.gov.hmcts.divorce.jsonlegacy;

import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.jsonlegacy.JsonLegacyCcdConfig.JsonOnlyRole;

@Component
public class JsonLegacyJavaOverrideEvent
    implements CCDConfig<LegacyJsonDataModel, State, JsonOnlyRole> {

  public static final String EVENT_ID = "json-legacy-java-override";
  public static final String MARKER = "java-configured-about-to-submit";

  @Override
  public Set<String> caseTypeIds() {
    return Set.of(JsonLegacyCcdConfig.CASE_TYPE_A, JsonLegacyCcdConfig.CASE_TYPE_B);
  }

  @Override
  public void configure(ConfigBuilder<LegacyJsonDataModel, State, JsonOnlyRole> builder) {
    builder.event(EVENT_ID)
        .forAllStates()
        .name("Java override of JSON event")
        .description("Java override of JSON event")
        .aboutToSubmitCallback(this::aboutToSubmit);
  }

  private AboutToStartOrSubmitResponse<LegacyJsonDataModel, State> aboutToSubmit(
      CaseDetails<LegacyJsonDataModel, State> details,
      CaseDetails<LegacyJsonDataModel, State> detailsBefore
  ) {
    details.getData().setSetInAboutToSubmit(MARKER);
    return AboutToStartOrSubmitResponse.<LegacyJsonDataModel, State>builder()
        .data(details.getData())
        .build();
  }
}
