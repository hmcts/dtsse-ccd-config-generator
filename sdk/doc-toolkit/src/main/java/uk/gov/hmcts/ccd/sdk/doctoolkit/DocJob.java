package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.UUID;

/**
 * A render of a case's document, as the service's {@link DocJobHandler} sees it.
 *
 * @param id the job, the same for every version of the case's document
 * @param caseReference the case the document belongs to
 * @param key the key it was {@link DocJobs#submit submitted} with
 * @param caseRevision the case's revision when the request was submitted: the document shows the
 *     case as it was then, and stays accurate until a later event changes the request. It only
 *     increases from one version to the next, so it orders a document's versions.
 */
public record DocJob(UUID id, long caseReference, String key, long caseRevision) {
}
