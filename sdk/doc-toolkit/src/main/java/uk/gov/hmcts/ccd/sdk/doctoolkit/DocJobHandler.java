package uk.gov.hmcts.ccd.sdk.doctoolkit;

/**
 * Receives each version of the documents {@link DocJobs#submit submitted}. A service using jobs
 * provides exactly one bean; it tells documents apart by {@link DocJob#key()}.
 *
 * <p>It runs in one database transaction with the worker's own bookkeeping, using the
 * application's transaction manager, and only while the worker holds the job and no newer request
 * has been submitted: a superseded render never reaches it, so versions arrive in the order they
 * were submitted. If it throws, the transaction rolls back and the job is rendered again later.
 * The document is attached to the case only once it returns.
 *
 * <p>What it is given is the service's: the toolkit never deletes it. A render that fails is
 * logged, and the service keeps the version it has; the next submit tries again.
 *
 * <pre>{@code
 * @Component
 * class Bundles implements DocJobHandler {
 *   public void completed(DocJob job, UploadedDoc uploaded) {
 *     versions.append(job.caseReference(), job.key(), uploaded);
 *   }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface DocJobHandler {

  /** A new version of the document, stored in CDAM. */
  void completed(DocJob job, UploadedDoc uploaded) throws Exception;
}
