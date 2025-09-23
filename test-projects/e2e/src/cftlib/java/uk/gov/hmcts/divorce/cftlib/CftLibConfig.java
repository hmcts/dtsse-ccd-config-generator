package uk.gov.hmcts.divorce.cftlib;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.rse.ccd.lib.ControlPlane;
import uk.gov.hmcts.rse.ccd.lib.Database;
import uk.gov.hmcts.rse.ccd.lib.api.CFTLib;
import uk.gov.hmcts.rse.ccd.lib.api.CFTLibConfigurer;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;

@Component
public class CftLibConfig implements CFTLibConfigurer {

    @Autowired
    @Lazy
    CCDDefinitionGenerator configWriter;

    @Override
    public void configure(CFTLib lib) throws Exception {
        HashMap<String, List<String>> users = new HashMap<>();
        users.put("DivCaseWorkerUser@AAT.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-courtadmin_beta"));
        users.put("TEST_CASE_WORKER_USER@mailinator.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-courtadmin_beta"));
        users.put("TEST_SOLICITOR@mailinator.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("TEST_SOLICITOR2@mailinator.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("TEST_JUDGE@mailinator.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-judge"));
        users.put("dummysystemupdate@test.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-systemupdate"));
        users.put("role.assignment.admin@gmail.com", List.of("caseworker"));
        users.put("data.store.idam.system.user@gmail.com", List.of("caseworker"));
        users.put("applicant2@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitora@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitorb@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitorc@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitord@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitore@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitorf@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitorg@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitorh@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitori@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("solicitorj@gmail.com", List.of("caseworker", "caseworker-divorce", "caseworker-divorce-solicitor"));
        users.put("divorce_as_caseworker_admin@mailinator.com", List.of("caseworker-divorce", "caseworker-divorce-superuser"));
        users.put("divorce_citizen@mailinator.com", List.of("citizen"));

        for (var entry : users.entrySet()) {
            lib.createIdamUser(entry.getKey(), entry.getValue().toArray(new String[0]));
            lib.createProfile(entry.getKey(), "DIVORCE", "NO_FAULT_DIVORCE", "Submitted");
        }

        lib.createRoles(
            "caseworker-divorce-courtadmin_beta",
            "caseworker-divorce-superuser",
            "caseworker-divorce-courtadmin-la",
            "caseworker-divorce-courtadmin",
            "caseworker-divorce-solicitor",
            "caseworker-divorce-judge",
            "caseworker-divorce-pcqextractor",
            "caseworker-divorce-systemupdate",
            "caseworker-divorce-bulkscan",
            "caseworker-caa",
            "caseworker-approver",
            "citizen",
            "caseworker-divorce",
            "caseworker",
            "payments",
            "pui-case-manager",
            "pui-finance-manager",
            "pui-organisation-manager",
            "pui-user-manager"
        );

        // Generate CCD definitions
        configWriter.generateAllCaseTypesToJSON(new File("build/definitions"));

        File source = new File("ccd-definitions");
        File dest = new File("build/definitions/NFD");
        try {
            FileUtils.copyDirectory(source, dest);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Import CCD definitions
        lib.importJsonDefinition(new File("build/definitions/NFD"));
//        lib.importJsonDefinition(new File("build/definitions/NO_FAULT_DIVORCE_BulkAction"));
        lib.dumpDefinitionSnapshots();
    }
}
