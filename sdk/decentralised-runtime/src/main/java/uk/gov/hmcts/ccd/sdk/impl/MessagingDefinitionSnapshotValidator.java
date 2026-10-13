package uk.gov.hmcts.ccd.sdk.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ccd.messaging", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class MessagingDefinitionSnapshotValidator implements InitializingBean {

  private final DefinitionRegistry definitionRegistry;

  @Override
  public void afterPropertiesSet() {
    definitionRegistry.requireDefinitions();
  }
}
