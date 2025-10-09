package uk.gov.hmcts.divorce.cftlib;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.rse.ccd.lib.api.CFTLib;
import uk.gov.hmcts.rse.ccd.lib.api.CFTLibConfigurer;

@Component
public class CftLibConfig implements CFTLibConfigurer {

    private static final String CASE_TYPE = "FT_Decentralisation";
    private static final String JURISDICTION = "BEFTA_MASTER";
    private static final String INITIAL_STATE = "CaseCreated";
    private static final String CASEWORKER_EMAIL = "master.caseworker@gmail.com";
    private static final String PRIVATE_CASEWORKER_EMAIL =
        System.getenv().getOrDefault("CCD_PRIVATE_CASEWORKER_EMAIL", "ccd_private_caseworker_email@local");
    private static final String CASEWORKER_AUTOTEST_EMAIL =
        System.getenv().getOrDefault("CCD_CASEWORKER_AUTOTEST_EMAIL", "ccd_caseworker_autotest_email@local");
    private static final String BEFTA_CASEWORKER_ONE_EMAIL =
        System.getenv().getOrDefault("CCD_BEFTA_CASEWORKER_1_EMAIL", "befta.caseworker.1@gmail.com");
    private static final String IMPORTER_EMAIL = "ccd.importer@local";
    private static final String ROLE_ASSIGNMENT_EMAIL = "role.assignment.admin@local";

    @Override
    public void configure(CFTLib lib) throws Exception {
        lib.createRoles(
            "caseworker",
            "caseworker-befta_master",
            "caseworker-befta_jurisdiction_1",
            "caseworker-caa",
            "ccd-import"
        );

        lib.createIdamUser(CASEWORKER_EMAIL, "caseworker", "caseworker-befta_master");
        lib.createProfile(CASEWORKER_EMAIL, JURISDICTION, CASE_TYPE, INITIAL_STATE);

        lib.createIdamUser(PRIVATE_CASEWORKER_EMAIL, "caseworker", "caseworker-befta_master");
        lib.createProfile(PRIVATE_CASEWORKER_EMAIL, JURISDICTION, CASE_TYPE, INITIAL_STATE);

        lib.createIdamUser(CASEWORKER_AUTOTEST_EMAIL, "caseworker", "caseworker-befta_master");
        lib.createProfile(CASEWORKER_AUTOTEST_EMAIL, JURISDICTION, CASE_TYPE, INITIAL_STATE);

        lib.createIdamUser(BEFTA_CASEWORKER_ONE_EMAIL,
            "caseworker",
            "caseworker-befta_master",
            "caseworker-befta_jurisdiction_1",
            "caseworker-caa");
        lib.createProfile(BEFTA_CASEWORKER_ONE_EMAIL, JURISDICTION, CASE_TYPE, INITIAL_STATE);

        lib.createIdamUser(IMPORTER_EMAIL, "caseworker", "ccd-import");
        lib.createProfile(IMPORTER_EMAIL, JURISDICTION, CASE_TYPE, INITIAL_STATE);

        lib.createIdamUser(ROLE_ASSIGNMENT_EMAIL, "caseworker", "caseworker-befta_master");
        lib.createProfile(ROLE_ASSIGNMENT_EMAIL, JURISDICTION, CASE_TYPE, INITIAL_STATE);

        lib.dumpDefinitionSnapshots();
    }
}
