package uk.gov.hmcts.divorce.jsonlegacy;

import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.divorce.divorcecase.model.State;

@Component
public class E2eJsonCaseView implements CaseView<E2eJson, State> {

    @Override
    public Set<String> caseTypeIds() {
        return Set.of(JsonLegacyCcdConfig.CASE_TYPE_A, JsonLegacyCcdConfig.CASE_TYPE_B);
    }

    @Override
    public E2eJson getCase(CaseViewRequest<State> request, E2eJson blobCase) {
        return blobCase;
    }
}
