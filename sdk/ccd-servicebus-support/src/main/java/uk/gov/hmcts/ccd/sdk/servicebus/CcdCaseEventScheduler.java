package uk.gov.hmcts.ccd.sdk.servicebus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(
    name = {"spring.jms.servicebus.enabled", "ccd.servicebus.scheduler-enabled"},
    havingValue = "true"
)
@RequiredArgsConstructor
public class CcdCaseEventScheduler {

  private final CcdCaseEventPublisher ccdCaseEventPublisher;

  @Scheduled(cron = "${ccd.servicebus.schedule:*/5 * * * * *}")
  public void publishPendingMessages() {
    log.debug("Scheduled CCD case event publishing task invoked");
    ccdCaseEventPublisher.publishPendingCaseEvents();
  }
}
