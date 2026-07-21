package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import java.util.Set;

/**
 * Defines when a decentralised service's cases are ready to enter CCD's disposal process.
 */
public interface RetainAndDisposePolicy {

  /**
   * Case types governed by this policy.
   */
  Set<String> caseTypes();

  /**
   * Returns the complete set of case references currently eligible for disposal.
   */
  List<Long> findCandidatesForDisposal();

  /**
   * Deletes service-owned data associated with a case before the SDK deletes its local CCD data.
   *
   * <p>The callback runs in the same database transaction as the deletion from {@code ccd.case_data}
   * and must only modify the service database. Services that rely entirely on cascading foreign keys
   * do not need to override it.</p>
   */
  default void dispose(long caseReference) {
    // Default database cleanup is provided by cascading foreign keys.
  }
}
