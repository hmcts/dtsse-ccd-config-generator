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
     * A complex-type member placed with the fluent {@code defaultValue(String)} setter must write the
     * member row's {@code DefaultValue} verbatim.
     *
     * <p>This is the column that decides whether a hand-written definition's member row can be
     * expressed in Java at all. finrem's {@code manageInterveners/intervener1} carries
     * {@code DefaultValue=[INTVRSOLICITOR1]} on
     * {@code intervenerOrganisation.OrgPolicyCaseAssignedRole}, and the retrofit converter has to emit
     * every column of a member row as Java or fall back to shipping the whole row as raw definition
     * JSON alongside the generated config.
     *
     * <p>The value is emitted as the raw string it was given: a role-shaped default like
     * {@code [INTVRSOLICITOR1]} is a case-role literal the definition names, not a
     * {@code HasRole} the config declares, so it must survive untranslated.
     */
    @Test
    public void writesTheMemberDefaultValueVerbatim() {
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder =
            newBuilder();
        builder.event("create")
            .forState(EventComplexMemberState.Open)
            .name("Create")
            .grant(CRU, LOCAL_AUTHORITY)
            .fields()
            .complex(EventComplexMemberCaseData::getContact)
            .optional(EventComplexMemberContact::getReference)
            .defaultValue("[INTVRSOLICITOR1]")
            .done();

        assertThat(memberRows(builder, "create", "contact"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("ListElementCode", "reference");
                assertThat(row).containsEntry("DefaultValue", "[INTVRSOLICITOR1]");
            });
    }

    /**
     * A member placed without the setter carries no {@code DefaultValue} column at all — not an empty
     * one — so a definition row that has no such column still compares equal to the generated row.
     */
    @Test
    public void omitsTheMemberDefaultValueColumnWhenUnset() {
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder =
            newBuilder();
        builder.event("create")
            .forState(EventComplexMemberState.Open)
            .name("Create")
            .grant(CRU, LOCAL_AUTHORITY)
            .fields()
            .complex(EventComplexMemberCaseData::getContact)
            .optional(EventComplexMemberContact::getReference)
            .done();

        assertThat(memberRows(builder, "create", "contact"))
            .singleElement()
            .satisfies(row -> assertThat(row).doesNotContainKey("DefaultValue"));
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
