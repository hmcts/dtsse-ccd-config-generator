package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.SetMultimap;

public interface HasAccessControl<R extends HasRole> {
  SetMultimap<R, Permission> getGrants();
}
