package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableSet;
import java.util.Map;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventId;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitResponse;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

/** Configuration mistakes an external event refuses when it is declared, rather than at runtime in CCD. */
public class ExternalEventConfigurationTest {

    private static final ExternalEventId<Void, String> NOTE = ExternalEventId.of("ext:note", String.class);

    @Test
    public void anExternalEventNeedsAStateToActOn() {
        assertThatThrownBy(() -> newBuilder().externalEvent(NOTE, submit -> accepted()).forStates())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs at least one state");
    }

    @Test
    public void onlyAnExternalIdMakesAnEventExternal() {
        Event.EventBuilder<CaseData, UserRole, State> event = Event.EventBuilder.builder(
            "addNote", CaseData.class, new PropertyUtils(), ImmutableSet.of(State.Open), ImmutableSet.of(State.Open));

        assertThatThrownBy(() -> event.external(String.class, submit -> accepted(), null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not external");
    }

    @Test
    public void anExternalEventKeepsTheHandlersItWasDeclaredWith() {
        Event.EventBuilder<CaseData, UserRole, State> event = Event.EventBuilder.builder(
            NOTE.id(), CaseData.class, new PropertyUtils(), ImmutableSet.of(State.Open), ImmutableSet.of(State.Open));
        event.external(String.class, submit -> accepted(), null, null);

        assertThatThrownBy(() -> event.submitHandler(payload -> SubmitResponse.defaultResponse()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.startHandler(payload -> payload.caseData()))
            .isInstanceOf(IllegalStateException.class);
    }

    private static ExternalSubmitResponse<State> accepted() {
        return ExternalSubmitResponse.accepted("Noted", "Noted");
    }

    private static ConfigBuilderImpl<CaseData, State, UserRole> newBuilder() {
        var config = new ResolvedCCDConfig<>(CaseData.class, State.class, UserRole.class, Map.of(),
            ImmutableSet.copyOf(State.values()));
        var builder = new ConfigBuilderImpl<>(config);
        builder.caseType("TEST_CASE_TYPE", "Test", "Test case type");
        return builder;
    }
}
