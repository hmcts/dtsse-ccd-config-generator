package uk.gov.hmcts.ccd.sdk.servicebus;

import static uk.gov.hmcts.ccd.sdk.servicebus.CcdMessageQueueRepository.MessageQueueCandidate;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
@ConditionalOnProperty(name = "spring.jms.servicebus.enabled", havingValue = "true")
public class CcdCaseEventPublisher {

  private final CcdMessageQueueRepository repository;
  private final JmsTemplate jmsTemplate;
  private final CcdServiceBusProperties properties;
  private final TransactionTemplate transactionTemplate;

  public CcdCaseEventPublisher(CcdMessageQueueRepository repository, JmsTemplate jmsTemplate,
                               CcdServiceBusProperties properties, PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.jmsTemplate = jmsTemplate;
    this.properties = properties;
    // Each batch owns its cases until it commits, independently of any caller's transaction.
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public void publishPendingCaseEvents() {
    String destination = properties.getDestination();
    if (destination == null || destination.isBlank()) {
      log.warn("No CCD Service Bus destination configured; skipping publish run");
      return;
    }

    int totalPublished = 0;
    PublishRun run = new PublishRun(properties.getBatchSize());

    while (!run.brokerUnavailable()) {
      BatchResult result = transactionTemplate.execute(status -> publishBatch(run));
      if (result == null) {
        break;
      }

      totalPublished += result.published();

      // A short claim and a short fetch mean nothing else is pending; anything newer waits for the next run.
      if (result.claimed() < properties.getBatchSize() && result.fetched() < properties.getBatchSize()) {
        break;
      }
    }

    if (run.brokerUnavailable()) {
      log.error("{} consecutive message_queue_candidates sends failed; aborting run", run.consecutiveFailedSends);
    }
    if (!run.failedCases.isEmpty()) {
      log.error("Failed to publish message_queue_candidates for {} case(s); they will be retried in a later run",
          run.failedCases.size());
    }

    if (properties.getPublishedRetentionDays() > 0) {
      LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getPublishedRetentionDays());
      int removed = repository.deletePublishedBefore(properties.getMessageType(), cutoff);
      if (removed > 0) {
        log.info("Removed {} published message_queue_candidates record(s) older than {}", removed, cutoff);
      }
    }

    if (totalPublished > 0) {
      log.info("Published {} CCD case event message(s) to {}", totalPublished, destination);
    } else {
      log.info("No CCD case event messages published in this run");
    }
  }

  private BatchResult publishBatch(PublishRun run) {
    List<Long> claimed = repository.claimCases(
        properties.getMessageType(), run.failedCases, properties.getBatchSize());
    if (claimed.isEmpty()) {
      return BatchResult.EMPTY;
    }

    List<MessageQueueCandidate> candidates = repository.findOwnedUnpublishedMessages(
        properties.getMessageType(), claimed, properties.getBatchSize());

    log.info("Preparing to publish {} message_queue_candidates record(s) to {}", candidates.size(),
        properties.getDestination());

    List<Long> publishedIds = new ArrayList<>(candidates.size());
    for (MessageQueueCandidate candidate : candidates) {
      if (run.brokerUnavailable()) {
        break;
      }
      if (run.failedCases.contains(candidate.reference())) {
        continue;
      }
      if (sendToServiceBus(candidate)) {
        publishedIds.add(candidate.id());
        run.consecutiveFailedSends = 0;
      } else {
        run.failedCases.add(candidate.reference());
        run.consecutiveFailedSends++;
      }
    }

    if (!publishedIds.isEmpty()) {
      repository.markPublished(publishedIds, LocalDateTime.now());
      log.info("Marked {} message_queue_candidates record(s) as published", publishedIds.size());
    }

    return new BatchResult(claimed.size(), candidates.size(), publishedIds.size());
  }

  // A failing case is skipped for the rest of the run after its first failed send, so it adds one failure.
  // Only a run of failures across as many cases as a batch, with no send succeeding between them, is treated
  // as the broker being unavailable; a few poisoned cases never stop the others being published.
  private static final class PublishRun {
    // Cases whose next message failed to send; not claimed again in this run so their messages stay in order.
    private final Set<Long> failedCases = new HashSet<>();
    private final int maxConsecutiveFailedSends;
    private int consecutiveFailedSends;

    private PublishRun(int maxConsecutiveFailedSends) {
      this.maxConsecutiveFailedSends = maxConsecutiveFailedSends;
    }

    private boolean brokerUnavailable() {
      return consecutiveFailedSends >= maxConsecutiveFailedSends;
    }
  }

  private record BatchResult(int claimed, int fetched, int published) {
    private static final BatchResult EMPTY = new BatchResult(0, 0, 0);
  }

  private boolean sendToServiceBus(MessageQueueCandidate candidate) {
    try {
      jmsTemplate.convertAndSend(properties.getDestination(), candidate.payload(),
          applyProperties(candidate.payload()));
      return true;
    } catch (Exception ex) {
      log.error("Failed to publish message_queue_candidates id {} reference {}", candidate.id(),
          candidate.reference(), ex);
      return false;
    }
  }

  private MessagePostProcessor applyProperties(JsonNode payload) {
    return message -> {
      for (MessageProperty property : MessageProperty.values()) {
        applyProperty(message, payload, property);
      }
      return message;
    };
  }

  private void applyProperty(Message message, JsonNode payload, MessageProperty property) throws JMSException {
    if (payload.hasNonNull(property.jsonKey)) {
      message.setStringProperty(property.jmsKey, payload.get(property.jsonKey).asText());
    }
  }

  private enum MessageProperty {
    JURISDICTION("JurisdictionId", "jurisdiction_id"),
    CASE_TYPE("CaseTypeId", "case_type_id"),
    CASE_ID("CaseId", "case_id"),
    SESSION("CaseId", "JMSXGroupID"),
    EVENT("EventId", "event_id");

    private final String jsonKey;
    private final String jmsKey;

    MessageProperty(String jsonKey, String jmsKey) {
      this.jsonKey = jsonKey;
      this.jmsKey = jmsKey;
    }
  }
}
