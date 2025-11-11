package uk.gov.hmcts.divorce.simplecase;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseData;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseState;

@Component
public class SimpleCaseView implements CaseView<SimpleCaseData, SimpleCaseState> {

    @Override
    public SimpleCaseData getCase(CaseViewRequest<SimpleCaseState> request, SimpleCaseData blobCase) {
        blobCase.setHyphenatedCaseRef(formatCaseReference(request.caseRef()));
        return blobCase;
    }

    private String formatCaseReference(long caseRef) {
        var digits = String.format("%016d", caseRef);
        return digits.replaceAll("(\\d{4})(?=\\d)", "$1-");
    }
}
