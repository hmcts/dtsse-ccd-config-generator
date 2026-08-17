package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class AccessType {

  private String accessTypeId;
  private String organisationProfileId;
  private boolean accessMandatory;
  private boolean accessDefault;
  private boolean display;
  private String description;
  private String hintText;
  private int displayOrder;
  private String liveTo;

  public static class AccessTypeBuilder {

    public static AccessTypeBuilder builder(String accessTypeId) {
      AccessTypeBuilder result = AccessType.builder();
      result.accessTypeId = accessTypeId;
      return result;
    }

    public AccessTypeBuilder organisationProfileId(String organisationProfileId) {
      this.organisationProfileId = organisationProfileId;
      return this;
    }

    public AccessTypeBuilder accessMandatory(boolean accessMandatory) {
      this.accessMandatory = accessMandatory;
      return this;
    }

    public AccessTypeBuilder accessDefault(boolean accessDefault) {
      this.accessDefault = accessDefault;
      return this;
    }

    public AccessTypeBuilder display(boolean display) {
      this.display = display;
      return this;
    }

    public AccessTypeBuilder description(String description) {
      this.description = description;
      return this;
    }

    public AccessTypeBuilder hintText(String hintText) {
      this.hintText = hintText;
      return this;
    }

    public AccessTypeBuilder displayOrder(int displayOrder) {
      this.displayOrder = displayOrder;
      return this;
    }

    public AccessTypeBuilder liveTo(String liveTo) {
      this.liveTo = liveTo;
      return this;
    }
  }
}
