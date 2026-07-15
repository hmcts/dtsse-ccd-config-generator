package uk.gov.hmcts.example.model.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A complex type using Lombok {@code @AllArgsConstructor} whose all-args constructor a subclass
 * ({@link RecoverableCostsSection}) calls positionally via {@code super(...)}, as Civil's
 * {@code FixedRecoverableCosts}/{@code FixedRecoverableCostsSection} do. Appending a synthesised
 * field would widen the generated all-args constructor from 2 to 3 args and leave the subclass's
 * two-arg {@code super(...)} call with no matching constructor (bug B4) — so the retrofit patch must
 * NOT synthesise into it, routing the member to a MANUAL_PLACEMENT gap instead.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoverableCosts {

  private String band;

  private String reasons;
}
