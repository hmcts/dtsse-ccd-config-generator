package uk.gov.hmcts.divorce.divorcecase.search;

import java.util.List;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCriteriaField;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Component
public class GlobalSearchCriteria implements CCDConfig<CaseData, State, UserRole> {

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder.searchCriteria()
            .fields(List.of(
                SearchCriteriaField.builder()
                    .otherCaseReference("searchCriteriaCaseReference")
                    .build()
            ))
            .build();
    }
}
