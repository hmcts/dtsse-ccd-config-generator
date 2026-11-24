package uk.gov.hmcts.ccd.sdk.doctoolkit;

/**
 * Receives finished jobs. The service provides one bean; the worker calls it once the PDF is in
 * CDAM and attached to the case, so the service only has to record the document:
 *
 * <pre>{@code
 * @Component
 * class BundleStored implements DocJobHandler {
 *   public void completed(DocJob job, DocRequest request, UploadedDoc uploaded) {
 *     bundles.save(job.caseId(), uploaded.document());
 *   }
 * }
 * }</pre>
 *
 * <p>If {@code completed} throws, it is called again later with the same document; the PDF is
 * not rendered or uploaded again. Make it safe to repeat.
 */
public interface DocJobHandler {

  /** The job's PDF is stored in CDAM and attached to the case. */
  void completed(DocJob job, DocRequest request, UploadedDoc uploaded) throws Exception;

  /**
   * The job was given up on; {@link DocJob#failure()} says why.
   */
  default void failed(DocJob job) throws Exception {
  }
}
