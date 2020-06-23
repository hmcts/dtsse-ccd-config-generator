package uk.gov.hmcts.ccd.sdk;

import org.junit.Test;
import org.reflections.Reflections;
import uk.gov.hmcts.ccd.sdk.types.CCDConfig;
import uk.gov.hmcts.ccd.sdk.types.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.types.Event;
import uk.gov.hmcts.ccd.sdk.types.Webhook;
import uk.gov.hmcts.example.missingcomplex.Applicant;
import uk.gov.hmcts.example.missingcomplex.MissingComplex;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import static org.assertj.core.api.Assertions.assertThat;

public class UnitTest {

    Reflections reflections = new Reflections("uk.gov.hmcts");
    ConfigGenerator generator = new ConfigGenerator(reflections, "uk.gov.hmcts");

    @Test

    public void multipleStatesPerEvent() {
        CCDConfig<CaseData, State, UserRole> cfg =
                builder -> builder.event("judgeDetails")
                        .forStates(State.PREPARE_FOR_HEARING, State.OPEN);

        ResolvedCCDConfig resolved = generator.resolveConfig(cfg);
        assertThat(resolved.events)
                .extracting(Event::getId)
                .contains("judgeDetails");

        assertThat(resolved.events)
                .flatExtracting(event -> event.getPreState()) // doesn't work with the method reference, inference issues?
                .containsExactlyInAnyOrder("PREPARE_FOR_HEARING", "Open");
    }

    @Test
    public void handlesEmptyConfig() {
        // Should not throw any exception.
        generator.resolveConfig(x -> {});
    }

    @Test
    public void webhookConvention() {
        ResolvedCCDConfig resolved = generator.resolveConfig(x -> {
            x.setWebhookConvention((webhookType, eventId) -> webhookType + "-" + eventId);
            x.event("eventId")
                    .forState(State.OPEN)
                    .allWebhooks();
        });

        assertThat(resolved.events.get(0).getAboutToStartURL()).isEqualTo(Webhook.AboutToStart + "-" + "eventId");
    }

    @Test
    public void npeBug() {
        generator.resolveConfig(new NPEBug());
    }
    class NPEBug implements CCDConfig<CaseData, State, UserRole> {

        @Override
        public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
            builder.event("addNotes")
                    .forStates(State.SUBMITTED, State.OPEN, State.DELETED)
                    .allWebhooks()
                    .fields()
                    .readonly(CaseData::getProceeding)
                    .complex(CaseData::getJudgeAndLegalAdvisor);
        }
    }

    @Test
    public void missingComplexBug() {
        class MissingBug implements CCDConfig<MissingComplex, State, UserRole> {
            @Override
            public void configure(ConfigBuilder<MissingComplex, State, UserRole> builder) {
            }
        }

        ResolvedCCDConfig resolved = generator.resolveConfig(new MissingBug());
        assertThat(resolved.types).containsKeys(Applicant.class);
    }
}
