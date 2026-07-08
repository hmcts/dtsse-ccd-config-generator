package uk.gov.hmcts.ccd.sdk.impl.cdam;

import java.util.List;

public record CaseDocumentsMetadata(
    String caseId,
    String caseTypeId,
    String jurisdictionId,
    List<DocumentHashToken> documentHashTokens
) {
}
