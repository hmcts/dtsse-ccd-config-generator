package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

public class AuthorisationCaseTypeGeneratorTest {

    private static final String CASE_TYPE = "TEST_CASE_TYPE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void fullShutterSetsAllRolesToDelete() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.shutterService();

        Map<String, String> crudByRole = generateCrudByRole(builder);

        assertThat(crudByRole.values()).containsOnly("D");
    }

    @Test
    public void excludedRolesKeepTheirPermissionsWhenServiceIsShuttered() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.shutterService();
        builder.shutterServiceExclude(UserRole.SYSTEM_UPDATE);

        Map<String, String> crudByRole = generateCrudByRole(builder);

        // The excluded role retains its normal case-type permissions.
        assertThat(crudByRole.get(UserRole.SYSTEM_UPDATE.getRole()))
            .isEqualTo(UserRole.SYSTEM_UPDATE.getCaseTypePermissions());
        // Every other non-case role is still shuttered to DELETE.
        assertThat(crudByRole.get(UserRole.LOCAL_AUTHORITY.getRole())).isEqualTo("D");
        assertThat(crudByRole.get(UserRole.CASE_ACCESS_APPROVER.getRole())).isEqualTo("D");
    }

    @Test
    public void excludeOverridesRoleSpecificShutter() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.shutterService(UserRole.SYSTEM_UPDATE);
        builder.shutterServiceExclude(UserRole.SYSTEM_UPDATE);

        Map<String, String> crudByRole = generateCrudByRole(builder);

        assertThat(crudByRole.get(UserRole.SYSTEM_UPDATE.getRole()))
            .isEqualTo(UserRole.SYSTEM_UPDATE.getCaseTypePermissions());
    }

    @Test
    public void caseRolesAreExcludedByDefault() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();

        Map<String, String> crudByRole = generateCrudByRole(builder);

        assertThat(crudByRole).doesNotContainKey(UserRole.CCD_SOLICITOR.getRole());
    }

    @Test
    public void includedCaseRoleGetsItsCaseTypePermissions() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.includeCaseRolesInCaseTypeAuthorisation(UserRole.CCD_SOLICITOR);

        Map<String, String> crudByRole = generateCrudByRole(builder);

        assertThat(crudByRole.get(UserRole.CCD_SOLICITOR.getRole()))
            .isEqualTo(UserRole.CCD_SOLICITOR.getCaseTypePermissions());
    }

    @Test
    public void includedCaseRoleIsShutteredLikeAnyOtherRole() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.shutterService();
        builder.includeCaseRolesInCaseTypeAuthorisation(UserRole.CCD_SOLICITOR);

        Map<String, String> crudByRole = generateCrudByRole(builder);

        assertThat(crudByRole.get(UserRole.CCD_SOLICITOR.getRole())).isEqualTo("D");
    }

    @Test
    public void includeCaseRolesInCaseTypeAuthorisationRejectsNonCaseRole() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> builder.includeCaseRolesInCaseTypeAuthorisation(UserRole.SYSTEM_UPDATE))
            .isInstanceOf(IllegalArgumentException.class);
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
    private Map<String, String> generateCrudByRole(ConfigBuilderImpl<CaseData, State, UserRole> builder) {
        ResolvedCCDConfig<CaseData, State, UserRole> config = builder.build();
        new AuthorisationCaseTypeGenerator<CaseData, State, UserRole>().write(tmp.getRoot(), config);

        File output = new File(tmp.getRoot(), "AuthorisationCaseType.json");
        List<Map<String, Object>> rows =
            MAPPER.readValue(output, new TypeReference<List<Map<String, Object>>>() {});

        return rows.stream().collect(Collectors.toMap(
            row -> (String) row.get("UserRole"),
            row -> (String) row.get("CRUD"),
            (a, b) -> b));
    }
}
