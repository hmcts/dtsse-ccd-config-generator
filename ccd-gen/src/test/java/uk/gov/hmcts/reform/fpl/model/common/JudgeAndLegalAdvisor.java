package uk.gov.hmcts.reform.fpl.model.common;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.enums.JudgeOrMagistrateTitle;

@Data
@Builder
public class JudgeAndLegalAdvisor {
    @CCD(label = "Judge or magistrate's title")
    private final JudgeOrMagistrateTitle judgeTitle;
    @CCD(label = "Title")
    private final String otherTitle;
    @CCD(label = "Last name")
    private final String judgeLastName;
    @CCD(label = "Full name")
    private final String judgeFullName;
    @CCD(label = "Legal advisor's full name")
    private final String legalAdvisorName;
}
