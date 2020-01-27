package uk.gov.hmcts.ccd.sdk;

import org.junit.Test;
import org.reflections.Reflections;
import uk.gov.hmcts.ccd.sdk.types.CCDConfig;
import uk.gov.hmcts.ccd.sdk.types.Event;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import static org.assertj.core.api.Assertions.assertThat;

public class UnitTest {

    Reflections reflections = new Reflections("uk.gov.hmcts.reform");
    ConfigGenerator generator = new ConfigGenerator(reflections);

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
}
