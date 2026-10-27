package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

public class AuthorisationCaseFieldGeneratorTest {

    private static final String CASE_TYPE = "TEST_CASE_TYPE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aTabGrantsReadOnTheFieldsItPlaces() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = builderWithTabbedField();

        Set<String> rolesReadingTheTabField = rolesFor(builder, "gatekeeperEmail");

        // The tab names no roles, so every role in the case type can read what it places — even
        // though only HMCTS_ADMIN is granted the field through an event.
        assertThat(rolesReadingTheTabField).contains(UserRole.LOCAL_AUTHORITY.getRole());
    }

    @Test
    public void noImplicitTabReadAccessLeavesTheFieldToItsOwnGrants() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = builderWithTabbedField();
        builder.noImplicitTabReadAccess();

        Set<String> rolesReadingTheTabField = rolesFor(builder, "gatekeeperEmail");

        assertThat(rolesReadingTheTabField)
            .contains(UserRole.HMCTS_ADMIN.getRole())
            .doesNotContain(UserRole.LOCAL_AUTHORITY.getRole());
    }

    @Test
    public void noImplicitTabReadAccessLeavesSearchFieldsAlone() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = builderWithTabbedField();
        builder.noImplicitTabReadAccess();
        builder.searchInputFields().field("caseName", "Case name");

        // A role that can search on a field still has to be able to read it.
        assertThat(rolesFor(builder, "caseName")).contains(UserRole.LOCAL_AUTHORITY.getRole());
    }

    private ConfigBuilderImpl<CaseData, State, UserRole> builderWithTabbedField() {
        ResolvedCCDConfig<CaseData, State, UserRole> config = new ResolvedCCDConfig<>(
            CaseData.class, State.class, UserRole.class, Map.of(),
            ImmutableSet.copyOf(State.values()));
        ConfigBuilderImpl<CaseData, State, UserRole> builder = new ConfigBuilderImpl<>(config);
        builder.caseType(CASE_TYPE, "Test", "Test case type");

        builder.event("edit")
            .forState(State.Open)
            .name("Edit")
            .grant(Permission.CRU, UserRole.HMCTS_ADMIN)
            .grant(Permission.CRU, UserRole.LOCAL_AUTHORITY)
            .fields()
            .optional(CaseData::getCaseName);

        builder.event("admin")
            .forState(State.Open)
            .name("Admin")
            .grant(Permission.CRU, UserRole.HMCTS_ADMIN)
            .fields()
            .optional(CaseData::getGatekeeperEmail);

        builder.tab("overview", "Overview").field(CaseData::getGatekeeperEmail);

        return builder;
    }

    @SneakyThrows
    private Set<String> rolesFor(
        ConfigBuilderImpl<CaseData, State, UserRole> builder, String caseField) {
        ResolvedCCDConfig<CaseData, State, UserRole> config = builder.build();
        new AuthorisationCaseFieldGenerator<CaseData, State, UserRole>().write(tmp.getRoot(), config);

        File folder = new File(tmp.getRoot(), "AuthorisationCaseField");
        return java.util.Arrays.stream(folder.listFiles())
            .flatMap(file -> rows(file).stream())
            .filter(row -> caseField.equals(row.get("CaseFieldID")))
            .map(row -> (String) row.get("UserRole"))
            .collect(Collectors.toSet());
    }

    @SneakyThrows
    private List<Map<String, Object>> rows(File file) {
        return MAPPER.readValue(file, new TypeReference<List<Map<String, Object>>>() {});
    }
}
