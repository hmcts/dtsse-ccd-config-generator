package uk.gov.hmcts.ccd.sdk.api;

import java.util.Set;
import uk.gov.hmcts.ccd.sdk.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket.WorkBasketBuilder;

public interface ConfigBuilder<T, S, R extends HasRole> {

  EventTypeBuilder<T, R, S> event(String id);

  void caseType(String caseType, String name, String description);

  void jurisdiction(String id, String name, String description);

  void setEnvironment(String env);

  void grant(S state, Set<Permission> permissions, R... role);

  void grantHistory(S state, R... role);

  void prefix(S state, String prefix);

  FieldBuilder<?, ?, ?, ?> field(String id);

  void caseField(String id, String showCondition, String type, String typeParam, String label);

  void caseField(String id, String label, String type, String collectionType);

  void caseField(String id, String label, String type);

  TabBuilder<T, R> tab(String tabId, String tabLabel);

  WorkBasketBuilder<T, R> workBasketResultFields();

  WorkBasketBuilder<T, R> workBasketInputFields();

  SearchBuilder<T, R> searchResultFields();

  SearchBuilder<T, R> searchInputFields();

  RoleBuilder<R> role(R... role);

  void setCallbackHost(String s);
}
