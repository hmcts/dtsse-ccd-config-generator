package uk.gov.hmcts.rt.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * A complex type in the sscs {@code Appeal}/prl {@code WithoutNoticeOrderDetails} shape: {@code @Data}
 * {@code @Builder} whose builder Lombok binds to a hand-written {@code @JsonCreator} constructor. The
 * definition declares a member ({@code boundNote}) this class has no field for, so the patch must
 * synthesise the field AND widen the bound constructor to take it — otherwise the generated builder
 * passes one more argument than the constructor declares.
 *
 * <p>{@link BoundPartyCaller} constructs this class positionally, exactly as prl's own
 * {@code UrgencyGeneratorTest} does, so this fixture also compile-proves the narrow delegating overload
 * the patch adds alongside the widened constructor. Without it, the round-trip's compile step fails.
 */
@Data
@Builder(toBuilder = true)
public class BoundParty {

  private String boundName;

  @JsonCreator
  public BoundParty(@JsonProperty("boundName") String boundName) {
    this.boundName = boundName;
  }
}
