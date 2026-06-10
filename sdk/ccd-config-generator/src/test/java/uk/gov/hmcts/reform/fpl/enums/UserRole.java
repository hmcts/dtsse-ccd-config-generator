package uk.gov.hmcts.reform.fpl.enums;

import com.google.common.collect.ImmutableList;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessType;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

import java.util.List;

public enum UserRole implements HasRole {
    LOCAL_AUTHORITY("caseworker-publiclaw-solicitor"),
    HMCTS_ADMIN("caseworker-publiclaw-courtadmin"),
    CAFCASS("caseworker-publiclaw-cafcass"),
    SYSTEM_UPDATE("caseworker-publiclaw-systemupdate"),
    BULK_SCAN("caseworker-publiclaw-bulkscan", "R"),
    BULK_SCAN_SYSTEM_UPDATE("caseworker-publiclaw-bulkscansystemupdate"),
    CASE_ACCESS_ADMINISTRATOR("caseworker-caa"),
    CASE_ACCESS_APPROVER("caseworker-approver", "CRU", AccessGroups.SOLICITOR_ORG_POLICY),
    @CCD(label = "Solicitor", hint = "Solicitor role")
    CCD_SOLICITOR("[SOLICITOR]");


    private final String role;
    private final String casetypePermissions;
    private final CCDAccessType accessType;

    UserRole(String role) {
        this(role, "CRU");
    }

    UserRole(String role, String casetypePermissions) {
        this(role, casetypePermissions, null);
    }

    UserRole(String role, String casetypePermissions, CCDAccessType accessType) {
        this.role = role;
        this.casetypePermissions = casetypePermissions;
        this.accessType = accessType;
    }

    public String getRole() {
        return this.role;
    }

    @Override
    public String getCaseTypePermissions() {
        return casetypePermissions;
    }

    @Override
    public CCDAccessType getAccessType() {
        return accessType;
    }

    public List<String> getRoles() {
        return ImmutableList.of("caseworker", "caseworker-publiclaw", this.role);
    }
}
