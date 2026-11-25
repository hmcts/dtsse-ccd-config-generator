package uk.gov.hmcts.ccd.sdk.doctoolkit;

/**
 * Receives finished jobs. A service using jobs provides exactly one bean; it tells jobs apart by
 * {@link DocJob#key()}.
 *
 * <p>Each call runs in one database transaction with the worker's own bookkeeping, using the
 * application's transaction manager. If the worker turns out not to hold the job any more (its
 * lease expired, or a newer revision of a kept-up-to-date document arrived), the transaction
 * rolls back, the handler's writes with it, and the worker deletes what it uploaded. So a handler
 * only ever commits current results, once per job.
 *
 * <pre>{@code
 * @Component
 * class Bundles implements DocJobHandler {
 *   public void completed(DocJob job, UploadedDoc uploaded) {
 *     versions.append(job.caseReference(), job.key().orElseThrow(),
 *         job.revision().getAsLong(), uploaded);
 *   }
 *   public void failed(DocJob job) { ... }
 *   public void removed(DocJob job) { ... }
 * }
 * }</pre>
 */
public interface DocJobHandler {

  /**
   * The job's PDF is stored in CDAM and attached to the case. If this throws, it is called again
   * later with the same document, without rendering again.
   */
  void completed(DocJob job, UploadedDoc uploaded) throws Exception;

  /**
   * The job was given up on; {@link DocJob#failure()} says why.
   */
  void failed(DocJob job) throws Exception;

  /**
   * A kept-up-to-date document's definition no longer gives the case a document. The toolkit
   * deletes nothing: documents already stored stay the service's.
   */
  void removed(DocJob job) throws Exception;
}
