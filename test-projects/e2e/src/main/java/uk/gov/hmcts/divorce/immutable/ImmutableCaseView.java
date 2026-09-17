package uk.gov.hmcts.divorce.immutable;

import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.divorce.divorcecase.model.ImmutableCaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;

@Component
public class ImmutableCaseView implements CaseView<ImmutableCaseData, State> {

    @Override
    public Set<String> caseTypeIds() {
        return Set.of(ImmutableCaseConfiguration.CASE_TYPE);
    }

    @Override
    public ImmutableCaseData getCase(CaseViewRequest<State> request, ImmutableCaseData blobCase) {
        return blobCase;
    }
}
