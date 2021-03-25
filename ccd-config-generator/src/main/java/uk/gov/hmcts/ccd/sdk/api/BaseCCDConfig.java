package uk.gov.hmcts.ccd.sdk.api;

import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket.WorkBasketBuilder;

public abstract class BaseCCDConfig<Model, State,
    Role extends HasRole> implements
    CCDConfig<Model, State, Role>, ConfigBuilder<Model, State, Role> {

  private ConfigBuilder<Model, State, Role> builder;

  @Override
  public void configure(ConfigBuilder<Model, State, Role> builder) {
    this.builder = builder;
    configure();
  }

  protected abstract void configure();

  @Override
  public EventTypeBuilder<Model, Role, State> event(String id) {
    return builder.event(id);
  }

  @Override
  public void caseType(String caseType, String name, String description) {
    builder.caseType(caseType, name, description);
  }

  @Override
  public void jurisdiction(String id, String name, String description) {
    builder.jurisdiction(id, name, description);
  }

  @Override
  public void grantHistory(State state, Role... roles) {
    builder.grantHistory(state, roles);
  }

  @Override
  public void grant(State state, Set<Permission> permissions, Role... roles) {
    builder.grant(state, permissions, roles);
  }

  @Override
  public void prefix(State state, String prefix) {
    builder.prefix(state, prefix);
  }

  @Override
  public FieldBuilder<?, ?, ?> field(String id) {
    return builder.field(id);
  }

  @Override
  public void caseField(String id, String label, String type, String collectionType) {
    builder.caseField(id, label, type, collectionType);
  }

  @Override
  public void caseField(String id, String label, String type) {
    builder.caseField(id, label, type);
  }

  @Override
  public void caseField(String id, String showCondition, String type, String typeParam,
      String label) {
    builder.caseField(id, showCondition, type, typeParam, label);
  }

  @Override
  public void setWebhookConvention(WebhookConvention convention) {
    builder.setWebhookConvention(convention);
  }

  @Override
  public void setEnvironment(String env) {
    builder.setEnvironment(env);
  }

  public TabBuilder<Model, Role> tab(String tabId, String tabLabel) {
    return builder.tab(tabId, tabLabel);
  }

  @Override
  public WorkBasketBuilder<Model, Role> workBasketResultFields() {
    return builder.workBasketResultFields();
  }

  @Override
  public WorkBasketBuilder<Model, Role> workBasketInputFields() {
    return builder.workBasketInputFields();
  }


  @Override
  public SearchBuilder<Model, Role> searchResultFields() {
    return builder.searchResultFields();
  }

  @Override
  public SearchBuilder<Model, Role> searchInputFields() {
    return builder.searchInputFields();
  }

  @Override
  public RoleBuilder<Role> role(Role... roles) {
    return builder.role(roles);
  }

  @Override
  public void add(Set<CaseRole.CaseRoleBuilder> caseRoleBuilders) {
    builder.add(caseRoleBuilders);
  }
}

