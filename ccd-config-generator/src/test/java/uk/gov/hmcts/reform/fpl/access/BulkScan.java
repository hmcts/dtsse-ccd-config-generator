package uk.gov.hmcts.reform.fpl.access;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.BULK_SCAN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.BULK_SCAN_SYSTEM_UPDATE;

public class BulkScan implements HasAccessControl<UserRole> {
  @Override
  public SetMultimap<UserRole, Permission> getGrants() {
    SetMultimap<UserRole, Permission> grants = HashMultimap.create();
    grants.putAll(BULK_SCAN, CRU);
    grants.putAll(BULK_SCAN_SYSTEM_UPDATE, CRU);
    return grants;
  }
}
