package uk.gov.hmcts.ccd.sdk.types;

import uk.gov.hmcts.ccd.sdk.types.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.types.WorkBasket.WorkBasketBuilder;

public interface ConfigBuilder<T, S, R extends HasRole> {

  EventTypeBuilder<T, R, S> event(String id);

  void caseType(String caseType);

  void setEnvironment(String env);

  // TODO: require enums as additional generic type params.
  void grant(S state, String permissions, R role);

  void blacklist(S state, R... role);

  void prefix(S state, String prefix);

  void caseField(String id, String showCondition, String type, String typeParam, String label);

  void caseField(String id, String label, String type, String collectionType);

  void caseField(String id, String label, String type);

  // Webhooks can follow a convention derived from the webhook type and event id.
  void setWebhookConvention(WebhookConvention convention);

  TabBuilder tab(String tabId, String tabLabel);

  WorkBasketBuilder workBasketResultFields();

  WorkBasketBuilder workBasketInputFields();

  void roleExtends(HasRole child, HasRole parent);
}
