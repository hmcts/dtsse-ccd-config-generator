package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

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
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.EventComplexMemberCaseData;
import uk.gov.hmcts.reform.EventComplexMemberState;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

public class CaseEventGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * The definition store reads about-to-start retries from {@code RetriesTimeoutAboutToStartEvent},
     * without the {@code URL} the other hooks' retries columns carry; any other name is ignored.
     */
    @Test
    public void writesAboutToStartRetriesToTheColumnTheDefinitionStoreReads() {
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder = newBuilder();
        builder.event("start")
            .forState(EventComplexMemberState.Open)
            .name("Start")
            .grant(CRU, LOCAL_AUTHORITY)
            .aboutToStartCallback(details ->
                AboutToStartOrSubmitResponse.<EventComplexMemberCaseData, EventComplexMemberState>builder().build())
            .retries(1, 2);

        assertThat(eventRow(builder, "start"))
            .containsEntry("RetriesTimeoutAboutToStartEvent", "1,2")
            .doesNotContainKey("RetriesTimeoutURLAboutToStartEvent");
    }

    /**
     * A handler's retries given with it apply to that hook alone, as {@code retries(Webhook, String)}
     * does.
     */
    @Test
    public void writesRetriesGivenWithAHandlerForThatHookOnly() {
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder = newBuilder();
        builder.event("submit")
            .forState(EventComplexMemberState.Open)
            .name("Submit")
            .grant(CRU, LOCAL_AUTHORITY)
            .aboutToStartCallback(details -> response())
            .aboutToSubmitCallback((details, before) -> response(), 5, 5);

        assertThat(eventRow(builder, "submit"))
            .containsEntry("RetriesTimeoutURLAboutToSubmitEvent", "5,5")
            .doesNotContainKey("RetriesTimeoutAboutToStartEvent");
    }

    /**
     * Retries are kept per hook whichever way the callback is set, so {@code retries(...)} applies to
     * a callback given by URL too.
     */
    @Test
    public void appliesRetriesSetSeparatelyToACallbackGivenByUrl() {
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder = newBuilder();
        builder.event("submit")
            .forState(EventComplexMemberState.Open)
            .name("Submit")
            .grant(CRU, LOCAL_AUTHORITY)
            .aboutToSubmitCallback("${CCD_DEF_URL}/submit")
            .retries(Webhook.AboutToSubmit, "1,2");

        assertThat(eventRow(builder, "submit"))
            .containsEntry("CallBackURLAboutToSubmitEvent", "${CCD_DEF_URL}/submit")
            .containsEntry("RetriesTimeoutURLAboutToSubmitEvent", "1,2");
    }

    /**
     * A page's mid-event retries go on its first {@code CaseEventToFields} row, for a handler as for
     * a URL, and a page given none carries no retries column.
     */
    @Test
    @SneakyThrows
    public void writesAPageHandlersRetriesOnItsFirstRow() {
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder = newBuilder();
        builder.event("paged")
            .forState(EventComplexMemberState.Open)
            .name("Paged")
            .grant(CRU, LOCAL_AUTHORITY)
            .fields()
            .page("checked", (details, before) -> response(), 3, 3)
            .optional(EventComplexMemberCaseData::getContact)
            .page("unchecked", (details, before) -> response())
            .label("uncheckedLabel", "Unchecked");

        new CaseEventToFieldsGenerator<EventComplexMemberCaseData, EventComplexMemberState, UserRole>()
            .write(tmp.getRoot(), builder.build());
        List<Map<String, Object>> rows = MAPPER.readValue(
            new File(new File(tmp.getRoot(), "CaseEventToFields"), "paged.json"), new TypeReference<>() {});

        assertThat(rows).filteredOn(row -> "checked".equals(row.get("PageID")))
            .singleElement()
            .satisfies(row -> assertThat(row).containsEntry("RetriesTimeoutURLMidEvent", "3,3"));
        assertThat(rows).filteredOn(row -> "unchecked".equals(row.get("PageID")))
            .allSatisfy(row -> assertThat(row).doesNotContainKey("RetriesTimeoutURLMidEvent"));
    }

    private static AboutToStartOrSubmitResponse<EventComplexMemberCaseData, EventComplexMemberState> response() {
        return AboutToStartOrSubmitResponse.<EventComplexMemberCaseData, EventComplexMemberState>builder().build();
    }

    private ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> newBuilder() {
        ResolvedCCDConfig<EventComplexMemberCaseData, EventComplexMemberState, UserRole> config =
            new ResolvedCCDConfig<>(
                EventComplexMemberCaseData.class, EventComplexMemberState.class, UserRole.class,
                Map.of(), ImmutableSet.copyOf(EventComplexMemberState.values()));
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder =
            new ConfigBuilderImpl<>(config);
        builder.caseType("TEST_CASE_TYPE", "Test", "Test case type");
        return builder;
    }

    @SneakyThrows
    private Map<String, Object> eventRow(
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder, String eventId) {
        new CaseEventGenerator<EventComplexMemberCaseData, EventComplexMemberState, UserRole>()
            .write(tmp.getRoot(), builder.build());
        File output = new File(new File(tmp.getRoot(), "CaseEvent"), eventId + ".json");
        List<Map<String, Object>> rows = MAPPER.readValue(output, new TypeReference<>() {});
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
