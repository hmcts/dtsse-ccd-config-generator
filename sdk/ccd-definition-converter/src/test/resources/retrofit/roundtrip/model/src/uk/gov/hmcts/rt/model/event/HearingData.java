package uk.gov.hmcts.rt.model.event;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;

/**
 * A prefixed {@code @JsonUnwrapped} event-data sub-object: its members flatten into CaseData's
 * namespace as {@code hearing + capitalize(member)} — {@code hearingType}, {@code hearingLength}.
 * The retrofit patch annotates the members here, and the companion config places them via a
 * {@code .complex(CaseData::getHearingData).x(HearingData::getType)} clustered leaf.
 *
 * <p>It also nests a prefix-less {@code @JsonUnwrapped} sub-object ({@link SettlementData}) to
 * exercise multi-level clustering: {@code settledAmount} is reached through TWO unwrap hops
 * (CaseData → HearingData → SettlementData) and must be placed via a nested {@code .complex().complex()}
 * chain, not a single-level ref to a getter HearingData does not declare.
 */
@Data
public class HearingData {

  private String type;

  private Integer length;

  @JsonUnwrapped
  private SettlementData settlement;
}
