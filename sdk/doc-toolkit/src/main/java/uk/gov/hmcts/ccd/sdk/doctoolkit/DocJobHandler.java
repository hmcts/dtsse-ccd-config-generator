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
 * <p>What it is given is the service's: the toolkit never deletes it. A document that cannot be
 * made is reported to {@link #failed}; submitting it again tries again.
 *
 * <pre>{@code
 * @Component
 * class Bundles implements DocJobHandler {
 *   public void completed(DocJob job, UploadedDoc uploaded) {
 *     versions.append(job.caseReference(), job.key(), job.caseRevision(), uploaded);
 *   }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface DocJobHandler {

  /** A new version of the document, stored in CDAM. */
  void completed(DocJob job, UploadedDoc uploaded) throws Exception;

  /**
   * The document could not be made: it failed permanently, or every attempt failed. Like
   * {@link #completed}, it runs in one transaction with the worker's bookkeeping, holding the case
   * lock, and only for the request still current, so a superseded render's failure is never
   * reported. If it throws, the job is failed without it and the error logged. Does nothing by
   * default, for documents whose failure needs no more than the log, such as a case bundle that
   * keeps its last version.
   */
  default void failed(DocJob job, DocJobFailure failure) throws Exception {
  }
}
