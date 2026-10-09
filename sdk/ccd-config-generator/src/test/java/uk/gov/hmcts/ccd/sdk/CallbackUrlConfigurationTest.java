package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.CallbackUrlsCaseData;
import uk.gov.hmcts.reform.EventColumnsState;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/** A hook is either an SDK handler or a URL; setting both is refused whichever comes first. */
public class CallbackUrlConfigurationTest {

    private static final String URL = "${CCD_DEF_IA_URL}/asylum/callback";

    @Test
    public void refusesAnAboutToStartUrlAfterAHandler() {
        assertRefused(e -> e.aboutToStartCallback(details -> response()).aboutToStartCallback(URL),
            "AboutToStart");
    }

    @Test
    public void refusesAnAboutToStartHandlerAfterAUrl() {
        assertRefused(e -> e.aboutToStartCallback(URL, 5).aboutToStartCallback(details -> response()),
            "AboutToStart");
    }

    @Test
    public void refusesAnAboutToStartUrlAlongsideAStartHandler() {
        assertRefused(e -> e.aboutToStartCallback(URL).startHandler(payload -> null), "AboutToStart");
        assertRefused(e -> e.startHandler(payload -> null).aboutToStartCallback(URL), "AboutToStart");
    }

    @Test
    public void refusesAnAboutToSubmitUrlAfterAHandler() {
        assertRefused(e -> e.aboutToSubmitCallback((details, before) -> response()).aboutToSubmitCallback(URL),
            "AboutToSubmit");
    }

    @Test
    public void refusesAnAboutToSubmitHandlerAfterAUrl() {
        assertRefused(e -> e.aboutToSubmitCallback(URL).aboutToSubmitCallback((details, before) -> response()),
            "AboutToSubmit");
    }

    @Test
    public void refusesASubmittedUrlAfterAHandler() {
        assertRefused(e -> e.submittedCallback((details, before) -> submitted()).submittedCallback(URL),
            "Submitted");
    }

    @Test
    public void refusesASubmittedHandlerAfterAUrl() {
        assertRefused(e -> e.submittedCallback(URL, 1, 2).submittedCallback((details, before) -> submitted()),
            "Submitted");
    }

    @Test
    public void refusesASubmitHandlerAlongsideASubmitUrl() {
        assertRefused(e -> e.aboutToSubmitCallback(URL).submitHandler(payload -> submitResponse()),
            "AboutToSubmit");
        assertRefused(e -> e.submittedCallback(URL).submitHandler(payload -> submitResponse()), "Submitted");
        assertRefused(e -> e.submitHandler(payload -> submitResponse()).submittedCallback(URL), "Submitted");
    }

    @Test
    public void refusesAMidEventUrlAfterAHandlerOnTheSamePage() {
        assertRefused(e -> e.fields()
            .page("one", (details, before) -> response())
            .page("one", URL)
            .done(), "MidEvent on page 'one'");
    }

    @Test
    public void refusesAMidEventHandlerAfterAUrlOnTheSamePage() {
        assertRefused(e -> e.fields()
            .page("one", URL, 5)
            .page("one", (details, before) -> response())
            .done(), "MidEvent on page 'one'");
    }

    private static void assertRefused(
        UnaryOperator<EventBuilder<CallbackUrlsCaseData, UserRole, EventColumnsState>> configure, String hook) {
        var event = newBuilder().event("anEvent").forAllStates();
        assertThatThrownBy(() -> configure.apply(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Event 'anEvent' has both a handler and a callback URL for " + hook);
    }

    private static AboutToStartOrSubmitResponse<CallbackUrlsCaseData, EventColumnsState> response() {
        return AboutToStartOrSubmitResponse.<CallbackUrlsCaseData, EventColumnsState>builder().build();
    }

    private static SubmittedCallbackResponse submitted() {
        return SubmittedCallbackResponse.builder().build();
    }

    private static SubmitResponse<EventColumnsState> submitResponse() {
        return SubmitResponse.<EventColumnsState>builder().build();
    }

    private static ConfigBuilderImpl<CallbackUrlsCaseData, EventColumnsState, UserRole> newBuilder() {
        var config = new ResolvedCCDConfig<>(CallbackUrlsCaseData.class, EventColumnsState.class, UserRole.class,
            Map.of(), ImmutableSet.copyOf(EventColumnsState.values()));
        var builder = new ConfigBuilderImpl<>(config);
        builder.caseType("TEST_CASE_TYPE", "Test", "Test case type");
        return builder;
    }
}
