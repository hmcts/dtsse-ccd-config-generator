package uk.gov.hmcts.reform.fpl.enums;

import ccd.sdk.types.HasLabel;
import lombok.Getter;

@Getter
public enum JudgeOrMagistrateTitle implements HasLabel {
    HER_HONOUR_JUDGE("Her Honour Judge"),
    HIS_HONOUR_JUDGE("His Honour Judge"),
    DISTRICT_JUDGE("District Judge"),
    DEPUTY_DISTRICT_JUDGE("Deputy District Judge"),
    DEPUTY_DISTRICT_JUDGE_MAGISTRATES_COURT("District Judge Magistrates Court"),
    MAGISTRATES("Magistrates (JP)"),
    OTHER("Other");

    private final String label;

    JudgeOrMagistrateTitle(String label) {
        this.label = label;
    }
}
