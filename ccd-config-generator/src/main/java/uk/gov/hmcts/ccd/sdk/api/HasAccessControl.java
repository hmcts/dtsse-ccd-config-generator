package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.SetMultimap;

public interface HasAccessControl {
  SetMultimap<HasRole, Permission> getGrants();
}
