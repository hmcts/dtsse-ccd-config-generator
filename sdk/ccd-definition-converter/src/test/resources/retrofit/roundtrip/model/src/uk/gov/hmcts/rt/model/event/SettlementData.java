package uk.gov.hmcts.rt.model.event;

import lombok.Data;

/**
 * A prefix-less {@code @JsonUnwrapped} sub-object nested INSIDE {@link HearingData} (which is itself
 * a prefixed {@code @JsonUnwrapped} member of {@code CaseData}). Its member flattens verbatim (no
 * prefix — an empty running prefix is kept, so {@code settledAmount} keeps its name). Because it is
 * reached through TWO unwrap hops, the companion config must place it via a nested
 * {@code .complex(CaseData::getHearingData).complex(HearingData::getSettlement)
 * .x(SettlementData::getSettledAmount)} chain — the multi-level clustering fix (a single-level ref
 * would emit {@code HearingData::getSettledAmount}, a getter that class does not declare).
 */
@Data
public class SettlementData {

  private String settledAmount;
}
