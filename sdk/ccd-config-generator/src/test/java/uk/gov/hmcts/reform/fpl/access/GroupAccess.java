package uk.gov.hmcts.reform.fpl.access;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.GroupRole.CASE_ACCESS_APPROVER_GROUP;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

/**
 * Grants an event to a group role rather than a member of the case's role class.
 *
 * <p>This is the wiring the access group needs to be useful: PRM mints a role assignment for
 * {@code caseworker-approver-group} off the AccessTypeRole, but that assignment only lets its holder
 * do anything if the group role is granted permissions the same way a UserRole would be. Granting
 * through {@link HasAccessControl} is what makes that possible — its grants are keyed on
 * {@link HasRole}, not on the case's role class, so a group role can be granted without having to be
 * declared as a {@code UserRole} of the case.</p>
 */
public class GroupAccess implements HasAccessControl {

  @Override
  public SetMultimap<HasRole, Permission> getGrants() {
    SetMultimap<HasRole, Permission> grants = HashMultimap.create();
    grants.putAll(CASE_ACCESS_APPROVER_GROUP, CRU);
    return grants;
  }
}
