package uk.gov.hmcts.ccd.sdk.bundling.managed;

import java.util.List;

/** Application-owned case discovery and document selection. */
public interface CaseBundleDefinition {
  String name();

  /** Select the current documents inside a short, repeatable-read transaction. */
  SelectedCaseBundle select(String caseKey);

  /**
   * Override to backfill eligible cases, using the same keys as select. Return up to limit keys
   * in a stable keyset order after the previous page's last key (null initially), and an empty
   * list at the end. Do not use offsets or filter on bundle/job state; the library skips handled
   * cases. Called inside a short database transaction.
   */
  default List<String> caseKeysAfter(String after, int limit) {
    throw new UnsupportedOperationException("Implement caseKeysAfter to backfill " + name());
  }
}
