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
import uk.gov.hmcts.reform.EventComplexMemberCaseData;
import uk.gov.hmcts.reform.EventComplexMemberContact;
import uk.gov.hmcts.reform.EventComplexMemberState;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

public class CaseEventToComplexTypesGeneratorTest {

    private static final String CASE_TYPE = "TEST_CASE_TYPE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * A member placed with {@code publish(...)} and {@code publishAs(...)} writes both columns, which
     * the definition store's {@code EventCaseFieldComplexTypeParser} reads on this sheet.
     */
    @Test
    public void writesTheMemberPublishAndPublishAs() {
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder =
            newBuilder();
        builder.event("create")
            .forState(EventComplexMemberState.Open)
            .name("Create")
            .grant(CRU, LOCAL_AUTHORITY)
            .fields()
            .complex(EventComplexMemberCaseData::getContact)
            .optional(EventComplexMemberContact::getReference)
            .publish(true)
            .publishAs("contactReference")
            .done();

        assertThat(memberRows(builder, "create", "contact"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("Publish", "Y");
                assertThat(row).containsEntry("PublishAs", "contactReference");
            });
    }

    /**
     * A member on a {@code publishToCamunda()} event carries no {@code Publish} column unless placed
     * with {@code publish(...)}: the event-level cascade writes {@code CaseEventToFields} only.
     */
    @Test
    public void omitsTheMemberPublishColumnsWhenUnset() {
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder =
            newBuilder();
        builder.event("create")
            .forState(EventComplexMemberState.Open)
            .name("Create")
            .grant(CRU, LOCAL_AUTHORITY)
            .publishToCamunda()
            .fields()
            .complex(EventComplexMemberCaseData::getContact)
            .optional(EventComplexMemberContact::getReference)
            .done();

        assertThat(memberRows(builder, "create", "contact"))
            .singleElement()
            .satisfies(row -> assertThat(row).doesNotContainKeys("Publish", "PublishAs"));
    }

    private ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> newBuilder() {
        ResolvedCCDConfig<EventComplexMemberCaseData, EventComplexMemberState, UserRole> config =
            new ResolvedCCDConfig<>(
                EventComplexMemberCaseData.class, EventComplexMemberState.class, UserRole.class,
                Map.of(), ImmutableSet.copyOf(EventComplexMemberState.values()));
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder =
            new ConfigBuilderImpl<>(config);
        builder.caseType(CASE_TYPE, "Test", "Test case type");
        return builder;
    }

    @SneakyThrows
    private List<Map<String, Object>> memberRows(
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder,
        String eventId, String caseFieldId) {
        ResolvedCCDConfig<EventComplexMemberCaseData, EventComplexMemberState, UserRole> config =
            builder.build();
        new CaseEventToComplexTypesGenerator<EventComplexMemberCaseData, EventComplexMemberState, UserRole>()
            .write(tmp.getRoot(), config);

        File output = new File(
            new File(new File(tmp.getRoot(), "CaseEventToComplexTypes"), eventId),
            caseFieldId + ".json");
        return MAPPER.readValue(output, new TypeReference<List<Map<String, Object>>>() {});
    }
}
