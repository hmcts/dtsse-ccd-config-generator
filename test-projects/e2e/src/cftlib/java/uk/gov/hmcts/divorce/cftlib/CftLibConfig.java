package uk.gov.hmcts.divorce.cftlib;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.simplecase.SimpleCaseConfiguration;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseState;
import uk.gov.hmcts.rse.ccd.lib.api.CFTLib;
import uk.gov.hmcts.rse.ccd.lib.api.CFTLibConfigurer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

@Component
public class CftLibConfig implements CFTLibConfigurer {

    private static final String BEFTA_CASE_TYPE = "FT_Decentralisation";
    private static final String BEFTA_JURISDICTION = "BEFTA_MASTER";
    private static final String BEFTA_INITIAL_STATE = "CaseCreated";
    private static final String CASEWORKER_EMAIL = "master.caseworker@gmail.com";
    private static final String PRIVATE_CASEWORKER_EMAIL =
        System.getenv().getOrDefault("CCD_PRIVATE_CASEWORKER_EMAIL", "ccd_private_caseworker_email@local");
    private static final String CASEWORKER_AUTOTEST_EMAIL =
        System.getenv().getOrDefault("CCD_CASEWORKER_AUTOTEST_EMAIL", "ccd_caseworker_autotest_email@local");
    private static final String BEFTA_CASEWORKER_ONE_EMAIL =
        System.getenv().getOrDefault("CCD_BEFTA_CASEWORKER_1_EMAIL", "befta.caseworker.1@gmail.com");
    private static final String IMPORTER_EMAIL = "ccd.importer@local";
    private static final String ROLE_ASSIGNMENT_EMAIL = "role.assignment.admin@local";

    @Autowired
    @Lazy
    CCDDefinitionGenerator configWriter;

    @Override
    public void configure(CFTLib lib) throws Exception {
        createRoles(lib);
        createBeftaUsers(lib);
        createSystemUsers(lib);
        createDivorceUsers(lib);
        configureRoleAssignments(lib);
        importDivorceDefinitions(lib);
    }

    private void createRoles(CFTLib lib) {
        Set<String> roles = new LinkedHashSet<>();
        roles.addAll(List.of(
            "caseworker",
            "caseworker-befta_master",
            "caseworker-befta_jurisdiction_1",
            "caseworker-caa",
            "ccd-import"
        ));
        roles.addAll(List.of(
            "caseworker-divorce-courtadmin_beta",
            "caseworker-divorce-superuser",
            "caseworker-divorce-courtadmin-la",
            "caseworker-divorce-courtadmin",
            "caseworker-divorce-solicitor",
            "caseworker-divorce-judge",
            "caseworker-divorce-pcqextractor",
            "caseworker-divorce-systemupdate",
            "caseworker-divorce-bulkscan",
            "caseworker-approver",
            "citizen",
            "caseworker-divorce",
            "payments",
            "pui-case-manager",
            "pui-finance-manager",
            "pui-organisation-manager",
            "pui-user-manager",
            "TTL_profile",
            "task-allocator"
        ));

        lib.createRoles(roles.toArray(new String[0]));
    }

    private void createBeftaUsers(CFTLib lib) {
        lib.createIdamUser(CASEWORKER_EMAIL, "caseworker", "caseworker-befta_master");
        lib.createProfile(CASEWORKER_EMAIL, BEFTA_JURISDICTION, BEFTA_CASE_TYPE, BEFTA_INITIAL_STATE);

        lib.createIdamUser(PRIVATE_CASEWORKER_EMAIL, "caseworker", "caseworker-befta_master");
        lib.createProfile(PRIVATE_CASEWORKER_EMAIL, BEFTA_JURISDICTION, BEFTA_CASE_TYPE, BEFTA_INITIAL_STATE);

        lib.createIdamUser(CASEWORKER_AUTOTEST_EMAIL, "caseworker", "caseworker-befta_master");
        lib.createProfile(CASEWORKER_AUTOTEST_EMAIL, BEFTA_JURISDICTION, BEFTA_CASE_TYPE, BEFTA_INITIAL_STATE);

        lib.createIdamUser(
            BEFTA_CASEWORKER_ONE_EMAIL,
            "caseworker",
            "caseworker-befta_master",
            "caseworker-befta_jurisdiction_1",
            "caseworker-caa"
        );
        lib.createProfile(BEFTA_CASEWORKER_ONE_EMAIL, BEFTA_JURISDICTION, BEFTA_CASE_TYPE, BEFTA_INITIAL_STATE);

        lib.createIdamUser(IMPORTER_EMAIL, "caseworker", "ccd-import");
        lib.createProfile(IMPORTER_EMAIL, BEFTA_JURISDICTION, BEFTA_CASE_TYPE, BEFTA_INITIAL_STATE);

        lib.createIdamUser(ROLE_ASSIGNMENT_EMAIL, "caseworker", "caseworker-befta_master");
        lib.createProfile(ROLE_ASSIGNMENT_EMAIL, BEFTA_JURISDICTION, BEFTA_CASE_TYPE, BEFTA_INITIAL_STATE);
    }

    private void createDivorceUsers(CFTLib lib) {
        Map<String, List<String>> users = Map.ofEntries(
            entry(
                "DivCaseWorkerUser@AAT.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-courtadmin_beta"
                )
            ),
            entry(
                "DivCaseSuperUser@AAT.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-superuser",
                    "caseworker-divorce-courtadmin_beta"
                )
            ),
            entry(
                "TEST_CASE_WORKER_USER@mailinator.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-courtadmin_beta",
                    "caseworker-st_cic"
                )
            ),
            entry(
                "TEST_SOLICITOR@mailinator.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "TEST_SOLICITOR2@mailinator.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "TEST_JUDGE@mailinator.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-judge"
                )
            ),
            entry(
                "dummysystemupdate@test.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-systemupdate"
                )
            ),
            entry("role.assignment.admin@gmail.com", List.of("caseworker")),
            entry("data.store.idam.system.user@gmail.com", List.of("caseworker")),
            entry(
                "applicant2@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitora@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitorb@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitorc@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitord@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitore@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitorf@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitorg@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitorh@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitori@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "solicitorj@gmail.com",
                List.of(
                    "caseworker",
                    "caseworker-divorce",
                    "caseworker-divorce-solicitor"
                )
            ),
            entry(
                "divorce_as_caseworker_admin@mailinator.com",
                List.of("caseworker-divorce", "caseworker-divorce-superuser")
            ),
            entry("divorce_citizen@mailinator.com", List.of("citizen"))
        );

        for (var entry : users.entrySet()) {
            lib.createIdamUser(entry.getKey(), entry.getValue().toArray(new String[0]));
            lib.createProfile(entry.getKey(), "DIVORCE", NoFaultDivorce.getCaseType(), "Submitted");
            lib.createProfile(entry.getKey(), "DIVORCE", SimpleCaseConfiguration.CASE_TYPE, SimpleCaseState.DRAFT.name());
        }
    }

    private void createSystemUsers(CFTLib lib) throws Exception {
      lib.createIdamUser("some_user@hmcts.net",
        "caseworker-wa",
        "caseworker-wa-configuration"
      );
      lib.createIdamUser("test-system-user@hmcts.net", "task-allocator", "caseworker-divorce-systemupdate");
    }
    private void importDivorceDefinitions(CFTLib lib) throws Exception {
        // Generate CCD definitions before importing them into the in-memory instance.
        configWriter.generateAllCaseTypesToJSON(new File("build/definitions"));

        lib.importJsonDefinition(new File("build/definitions/" + NoFaultDivorce.getCaseType()));
        lib.importJsonDefinition(new File("build/definitions/" + SimpleCaseConfiguration.CASE_TYPE));
        lib.dumpDefinitionSnapshots();
    }

    private void configureRoleAssignments(CFTLib lib) throws IOException {
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        try (var inputStream = resourceLoader.getResource("classpath:cftlib-am-role-assignments.json")
            .getInputStream()) {
            String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            lib.configureRoleAssignments(json);
        }
    }
}
