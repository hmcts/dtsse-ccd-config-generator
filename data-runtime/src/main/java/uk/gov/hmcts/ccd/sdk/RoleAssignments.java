package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleAssignments {

  private List<RoleAssignment> roleAssignments;
}
