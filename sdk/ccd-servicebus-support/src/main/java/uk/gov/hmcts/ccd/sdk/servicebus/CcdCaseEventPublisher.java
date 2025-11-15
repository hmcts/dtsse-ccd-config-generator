package uk.gov.hmcts.ccd.sdk.servicebus;

import static uk.gov.hmcts.ccd.sdk.servicebus.CcdMessageQueueRepository.MessageQueueCandidate;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
@ConditionalOnProperty(name = "spring.jms.servicebus.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CcdCaseEventPublisher {

  private final CcdMessageQueueRepository repository;
  private final JmsTemplate jmsTemplate;
  private final CcdServiceBusProperties properties;
  private final TransactionTemplate transactionTemplate;

  @Value("${ccd.servicebus.destination:}")
  private String destination;

  public void publishPendingCaseEvents() {
    if (destination == null || destination.isBlank()) {
      log.warn("No CCD Service Bus destination configured; skipping publish run");
      return;
    }

    int totalPublished = 0;

    while (true) {
      BatchResult result = transactionTemplate.execute(status -> publishBatch());
      if (result == null || result.fetched() == 0) {
        break;
      }

      totalPublished += result.published();

      if (result.published() == 0) {
        log.error("Failed to publish any message_queue_candidates record(s) in current batch; aborting run");
        break;
      }

      if (result.fetched() < properties.getBatchSize()) {
        break;
      }
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

  private BatchResult publishBatch() {
    List<MessageQueueCandidate> candidates =
        repository.findUnpublishedMessages(properties.getMessageType(), properties.getBatchSize());
    if (candidates.isEmpty()) {
      return BatchResult.EMPTY;
    }

    log.info("Preparing to publish {} message_queue_candidates record(s) to {}", candidates.size(), destination);

    List<Long> publishedIds = new ArrayList<>(candidates.size());
    for (MessageQueueCandidate candidate : candidates) {
      if (sendToServiceBus(candidate)) {
        publishedIds.add(candidate.id());
      }
    }

    if (!publishedIds.isEmpty()) {
      repository.markPublished(publishedIds, LocalDateTime.now());
      log.info("Marked {} message_queue_candidates record(s) as published", publishedIds.size());
    }

    return new BatchResult(candidates.size(), publishedIds.size());
  }

  private record BatchResult(int fetched, int published) {
    private static final BatchResult EMPTY = new BatchResult(0, 0);
  }

  private boolean sendToServiceBus(MessageQueueCandidate candidate) {
    try {
      jmsTemplate.convertAndSend(destination, candidate.payload(), applyProperties(candidate.payload()));
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
