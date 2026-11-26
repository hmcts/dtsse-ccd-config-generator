package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.UUID;

/**
 * A render of a case's document, as the service's {@link DocJobHandler} sees it.
 *
 * @param id the job, the same for every version of the case's document
 * @param caseReference the case the document belongs to
 * @param key the key it was {@link DocJobs#submit submitted} with
 */
public record DocJob(UUID id, long caseReference, String key) {
}
