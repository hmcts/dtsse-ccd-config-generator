package uk.gov.hmcts.reform.fpl.enums;

import com.google.common.collect.ImmutableList;
import uk.gov.hmcts.ccd.sdk.types.HasRole;

import java.util.List;

public enum UserRole implements HasRole {
    LOCAL_AUTHORITY("caseworker-publiclaw-solicitor"),
    HMCTS_ADMIN("caseworker-publiclaw-courtadmin"),
    CAFCASS("caseworker-publiclaw-cafcass"),
    GATEKEEPER("caseworker-publiclaw-gatekeeper"),
    JUDICIARY("caseworker-publiclaw-judiciary"),
    SYSTEM_UPDATE("caseworker-publiclaw-systemupdate"),
    BULK_SCAN("caseworker-publiclaw-bulkscan", "R"),
    BULK_SCAN_SYSTEM_UPDATE("caseworker-publiclaw-bulkscansystemupdate"),
    CCD_SOLICITOR("[SOLICITOR]"),
    CCD_LASOLICITOR("[LASOLICITOR]");


    private final String role;
    private final String casetypePermissions;

    UserRole(String role) {
        this(role, "CRU");
    }

    UserRole(String role, String casetypePermissions) {
        this.role = role;
        this.casetypePermissions = casetypePermissions;
    }

    public String getRole() {
        return this.role;
    }

    @Override
    public String getCaseTypePermissions() {
        return casetypePermissions;
    }

    public List<String> getRoles() {
        return ImmutableList.of("caseworker", "caseworker-publiclaw", this.role);
    }
}
