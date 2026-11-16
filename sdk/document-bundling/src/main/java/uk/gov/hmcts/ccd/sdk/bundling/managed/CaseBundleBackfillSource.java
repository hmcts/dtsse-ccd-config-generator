package uk.gov.hmcts.ccd.sdk.bundling.managed;

import java.util.List;

/** Application-owned discovery of eligible cases; the library queues them for backfill. */
public interface CaseBundleBackfillSource {
  /**
   * The name of a registered {@link CaseBundleDefinition}.
   */
  String definition();

  /**
   * Return up to limit case keys after the previous page's last key (null initially), in a stable
   * keyset ordering. Return an empty list at the end. Keys are the identifiers accepted by the
   * definition's select method. Do not use offset pagination or filter on bundle/job state: the
   * library skips cases already handled. Called inside a short database transaction.
   *
   * <p>Each process scans once while enabled; restarts scan again, using the queue to avoid repeat
   * work. New cases behind the scan's position need normal change notifications or a later scan.
   */
  List<String> caseKeysAfter(String after, int limit);
}
