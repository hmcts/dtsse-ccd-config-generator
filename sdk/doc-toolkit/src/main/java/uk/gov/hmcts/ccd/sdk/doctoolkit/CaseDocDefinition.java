package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.Optional;
import java.util.Set;

/**
 * A document kept up to date with its case, such as a case bundle. Register one as a Spring bean
 * (with {@code ccd.doc-toolkit.jobs.enabled=true}); after every case event of one of its case
 * types the toolkit calls {@link #build}, and queues a render when the request differs from the
 * one last queued. The service never detects changes itself.
 *
 * <p>{@code build} runs inside the event's transaction, under the case lock, on every event.
 * If it throws, the event fails. It must be:
 * <ul>
 *   <li>read-only: it changes no entity;</li>
 *   <li>deterministic: the same case state gives the same request (no timestamps, random ids or
 *       unordered collections);</li>
 *   <li>cheap and local: it reads the service's database and makes no remote calls. Fetching,
 *       converting and stitching happen later, in a worker.</li>
 * </ul>
 */
public interface CaseDocDefinition {

  /** Names the document, unique within the service, for example {@code case-bundle}. */
  String key();

  /** The case types it applies to; events of other case types skip it. */
  Set<String> caseTypes();

  /** The document the case should have now, or empty when it should have none. */
  Optional<PdfRequest> build(long caseReference);
}
