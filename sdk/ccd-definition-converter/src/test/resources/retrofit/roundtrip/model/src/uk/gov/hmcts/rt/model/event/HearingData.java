package uk.gov.hmcts.rt.model.event;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;
import uk.gov.hmcts.rt.model.common.Party;

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
 *
 * <p>{@code party} is a COMPLEX member reached through this prefixed unwrap, flattening to the CCD
 * field {@code hearingParty}. The definition grants an {@code AuthorisationComplexType} on
 * {@code hearingParty.fullName} — a grant on a complex field that has NO direct {@code CaseData}
 * getter (it lives here on {@code HearingData}). This exercises the delegating-getter synthesis: the
 * patch must add {@code CaseData.getHearingParty()} delegating to {@code getHearingData().getParty()}
 * so {@code grantComplexType(CaseData::getHearingParty, …)} resolves at generation, rather than
 * emitting a multi-hop lambda the SDK cannot resolve.
 */
@Data
public class HearingData {

  private String type;

  private Integer length;

  @JsonUnwrapped
  private SettlementData settlement;

  private Party party;
}
