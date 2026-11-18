package uk.gov.hmcts.reform.fpl.access;

import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

public class WriteWithoutReadAccess implements HasAccessControl {
  @Override
  public SetMultimap<HasRole, Permission> getGrants() {
    SetMultimap<HasRole, Permission> grants = HashMultimap.create();
    grants.putAll(LOCAL_AUTHORITY, Sets.immutableEnumSet(Permission.C, Permission.U));
    return grants;
  }
}
