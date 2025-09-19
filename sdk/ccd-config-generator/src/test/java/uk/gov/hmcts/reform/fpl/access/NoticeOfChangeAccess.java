package uk.gov.hmcts.reform.fpl.access;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CASE_ACCESS_ADMINISTRATOR;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CASE_ACCESS_APPROVER;

public class NoticeOfChangeAccess implements HasAccessControl {
  @Override
  public SetMultimap<HasRole, Permission> getGrants() {
    SetMultimap<HasRole, Permission> grants = HashMultimap.create();
    grants.putAll(CASE_ACCESS_ADMINISTRATOR, CRU);
    grants.putAll(CASE_ACCESS_APPROVER, CRU);
    return grants;
  }
}
