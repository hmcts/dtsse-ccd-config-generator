package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class AccessTypeRole {

  private String accessTypeId;
  private String organisationProfileId;
  private String organisationalRoleName;
  private String groupRoleName;
  private String caseAssignedRoleField;
  private boolean groupAccessEnabled;
  private String caseAccessGroupIdTemplate;
  private String liveTo;

  public static class AccessTypeRoleBuilder {

    public static AccessTypeRoleBuilder builder(String accessTypeId) {
      AccessTypeRoleBuilder result = AccessTypeRole.builder();
      result.accessTypeId = accessTypeId;
      return result;
    }

    public AccessTypeRoleBuilder organisationProfileId(String organisationProfileId) {
      this.organisationProfileId = organisationProfileId;
      return this;
    }

    public AccessTypeRoleBuilder organisationalRoleName(String organisationalRoleName) {
      this.organisationalRoleName = organisationalRoleName;
      return this;
    }

    public AccessTypeRoleBuilder groupRoleName(String groupRoleName) {
      this.groupRoleName = groupRoleName;
      return this;
    }

    public AccessTypeRoleBuilder caseAssignedRoleField(String caseAssignedRoleField) {
      this.caseAssignedRoleField = caseAssignedRoleField;
      return this;
    }

    public AccessTypeRoleBuilder groupAccessEnabled(boolean groupAccessEnabled) {
      this.groupAccessEnabled = groupAccessEnabled;
      return this;
    }

    public AccessTypeRoleBuilder caseAccessGroupIdTemplate(String caseAccessGroupIdTemplate) {
      this.caseAccessGroupIdTemplate = caseAccessGroupIdTemplate;
      return this;
    }

    public AccessTypeRoleBuilder liveTo(String liveTo) {
      this.liveTo = liveTo;
      return this;
    }
  }
}
