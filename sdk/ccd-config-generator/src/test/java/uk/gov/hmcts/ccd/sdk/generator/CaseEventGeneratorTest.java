package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

public class CaseEventGeneratorTest {

    private static final String CASE_TYPE = "TEST_CASE_TYPE";
    private static final String POST_CONDITION = "PostConditionState";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writesTheResolvedPostStateByDefault() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.event("transition").forStateTransition(State.Open, State.Submitted).name("Transition");

        assertThat(event(builder, "transition")).containsEntry(POST_CONDITION, "Submitted");
    }

    @Test
    public void postStateFromCallbackOmitsTheColumnEntirely() {
        // An absent PostConditionState is a third runtime behaviour, distinct from a concrete state
        // and from '*': the data store applies only the state the about-to-submit callback returned.
        // Writing any value here would force a definition-declared transition the hand-written
        // definition deliberately omitted, so the column must be absent, not empty or '*'.
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.event("callbackDecides")
            .forState(State.Open)
            .name("Callback decides")
            .postStateFromCallback();

        assertThat(event(builder, "callbackDecides")).doesNotContainKey(POST_CONDITION);
    }

    @Test
    public void postStateFromCallbackLeavesThePreStateAlone() {
        // Only the post-state column is suppressed: the event is still restricted to the states it
        // was declared for, so an event available in one state does not become available in all.
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.event("callbackDecides")
            .forStateTransition(State.Open, State.Submitted)
            .name("Callback decides")
            .postStateFromCallback();

        Map<String, Object> row = event(builder, "callbackDecides");
        assertThat(row).containsEntry("PreConditionState(s)", "Open");
        assertThat(row).doesNotContainKey(POST_CONDITION);
    }

    private ConfigBuilderImpl<CaseData, State, UserRole> newBuilder() {
        ResolvedCCDConfig<CaseData, State, UserRole> config = new ResolvedCCDConfig<>(
            CaseData.class, State.class, UserRole.class, Map.of(),
            ImmutableSet.copyOf(State.values()));
        ConfigBuilderImpl<CaseData, State, UserRole> builder = new ConfigBuilderImpl<>(config);
        builder.caseType(CASE_TYPE, "Test", "Test case type");
        return builder;
    }

    @SneakyThrows
    private Map<String, Object> event(ConfigBuilderImpl<CaseData, State, UserRole> builder, String eventId) {
        ResolvedCCDConfig<CaseData, State, UserRole> config = builder.build();
        new CaseEventGenerator<CaseData, State, UserRole>().write(tmp.getRoot(), config);

        File output = new File(new File(tmp.getRoot(), "CaseEvent"), eventId + ".json");
        List<Map<String, Object>> rows =
            MAPPER.readValue(output, new TypeReference<List<Map<String, Object>>>() {});
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
