package uk.gov.hmcts.divorce.jsonlegacy;

import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.divorce.divorcecase.model.State;

@Component
public class E2eJsonBCaseView implements CaseView<E2eJsonB, State> {

    @Override
    public Set<String> caseTypeIds() {
        return Set.of(JsonLegacyCcdConfig.CASE_TYPE_B);
    }

    @Override
    public E2eJsonB getCase(CaseViewRequest<State> request, E2eJsonB blobCase) {
        return blobCase;
    }
}
