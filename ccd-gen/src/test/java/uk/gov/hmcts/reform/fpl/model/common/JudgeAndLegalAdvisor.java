package uk.gov.hmcts.reform.fpl.model.common;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.enums.JudgeOrMagistrateTitle;

@Data
@Builder
public class JudgeAndLegalAdvisor {
    @CaseField(label = "Judge or magistrate's title")
    private final JudgeOrMagistrateTitle judgeTitle;
    @CaseField(label = "Title")
    private final String otherTitle;
    @CaseField(label = "Last name")
    private final String judgeLastName;
    @CaseField(label = "Full name")
    private final String judgeFullName;
    @CaseField(label = "Legal advisor's full name")
    private final String legalAdvisorName;
}
