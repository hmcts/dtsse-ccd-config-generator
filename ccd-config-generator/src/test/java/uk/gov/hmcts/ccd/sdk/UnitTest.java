package uk.gov.hmcts.ccd.sdk;

import org.junit.Test;
import org.reflections.Reflections;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
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
                        .forStates(State.PREPARE_FOR_HEARING, State.Open);

        ResolvedCCDConfig resolved = generator.resolveConfig(cfg);
        assertThat(resolved.events)
                .extracting(Event::getId)
                .contains("judgeDetails", "judgeDetailsOpen");
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
                    .forState("state")
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
                    .forStates(State.Submitted, State.Open, State.Deleted)
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
