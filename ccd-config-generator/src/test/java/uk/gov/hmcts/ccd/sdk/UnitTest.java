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

    @Test
    public void multipleStatesPerEvent() {
        CCDConfig<CaseData, State, UserRole> cfg =
                builder -> builder.event("judgeDetails")
                        .forStates(State.PREPARE_FOR_HEARING, State.Open);
        ConfigGenerator<CaseData, State, UserRole> generator = new ConfigGenerator<>(reflections, "uk.gov.hmcts");

        ResolvedCCDConfig<CaseData, State, UserRole> resolved = generator.resolveCCDConfig(cfg);
        assertThat(resolved.events)
                .extracting(Event::getId)
                .contains("judgeDetails", "judgeDetailsOpen");
    }

    @Test
    public void handlesEmptyConfig() {
        ConfigGenerator<CaseData, State, UserRole> generator = new ConfigGenerator<>(reflections, "uk.gov.hmcts");
        // Should not throw any exception.
        generator.resolveCCDConfig(x -> {});
    }

    @Test
    public void webhookConvention() {
        ConfigGenerator<CaseData, State, UserRole> generator = new ConfigGenerator<>(reflections, "uk.gov.hmcts");
        ResolvedCCDConfig<CaseData, State, UserRole> resolved = generator.resolveCCDConfig(x -> {
            x.setWebhookConvention((webhookType, eventId) -> webhookType + "-" + eventId);
            x.event("eventId")
                    .forState(State.Open)
                    .allWebhooks();
        });

        assertThat(resolved.events.get(0).getAboutToStartURL()).isEqualTo(Webhook.AboutToStart + "-" + "eventId");
    }

    @Test
    public void npeBug() {
        ConfigGenerator<CaseData, State, UserRole> generator = new ConfigGenerator<>(reflections, "uk.gov.hmcts");
        generator.resolveCCDConfig(new NPEBug());
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

        ConfigGenerator<MissingComplex, State, UserRole> generator = new ConfigGenerator<>(reflections, "uk.gov.hmcts");
        ResolvedCCDConfig<MissingComplex, State, UserRole> resolved = generator.resolveCCDConfig(new MissingBug());
        assertThat(resolved.types).containsKeys(Applicant.class);
    }
}
