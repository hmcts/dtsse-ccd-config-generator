package uk.gov.hmcts.ccd.sdk.servicebus;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@SpringBootTest(classes = CcdCaseEventPublisherIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
@Timeout(60)
class CcdCaseEventPublisherIntegrationTest {

  private static final long CASE_A = 1000000000000001L;
  private static final long CASE_B = 1000000000000002L;
  private static final long CASE_C = 1000000000000003L;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private CcdMessageQueueRepository repository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from ccd.case_data");
  }

  @Test
  void skipsRemainingMessagesOfAFailedCaseWhileOtherCasesProgress() {
    seedCases(CASE_A, CASE_B, CASE_C);
    long a1 = enqueue(CASE_A);
    long a2 = enqueue(CASE_A);
    long a3 = enqueue(CASE_A);
    long b1 = enqueue(CASE_B);
    long b2 = enqueue(CASE_B);
    long c1 = enqueue(CASE_C);

    var broker = new RecordingJmsTemplate(id -> id == a1);
    // The first batch holds only case A, so it publishes nothing, yet cases B and C must still be published.
    publisher(broker, 3).publishPendingCaseEvents();

    assertThat(broker.sentIds()).containsExactlyInAnyOrder(a1, b1, b2, c1);
    assertThat(publishedIds()).containsExactlyInAnyOrder(b1, b2, c1);

    var retry = new RecordingJmsTemplate(id -> false);
    publisher(retry, 3).publishPendingCaseEvents();

    assertThat(retry.sentIds()).containsExactly(a1, a2, a3);
    assertThat(publishedIds()).containsExactlyInAnyOrder(a1, a2, a3, b1, b2, c1);
  }

  @Test
  void publishesACaseWithMoreMessagesThanABatchInOneRun() {
    seedCases(CASE_A);
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      ids.add(enqueue(CASE_A));
    }

    var broker = new RecordingJmsTemplate(id -> false);
    publisher(broker, 3).publishPendingCaseEvents();

    assertThat(broker.sentIds()).containsExactlyElementsOf(ids);
    assertThat(unpublishedIds()).isEmpty();
  }

  @Test
  void poisonedCasesFillingBatchesDoNotStopOtherCasesBeingPublished() {
    List<Long> poisoned = List.of(3000000000000001L, 3000000000000002L, 3000000000000003L);
    List<Long> healthy = List.of(3000000000000004L, 3000000000000005L, 3000000000000006L);
    seedCases(poisoned.toArray(Long[]::new));
    seedCases(healthy.toArray(Long[]::new));
    Set<Long> poisonedIds = new HashSet<>();
    for (long reference : poisoned) {
      for (int i = 0; i < 4; i++) {
        poisonedIds.add(enqueue(reference));
      }
    }
    List<Long> healthyIds = healthy.stream().map(this::enqueue).toList();

    var broker = new RecordingJmsTemplate(poisonedIds::contains);
    // Each poisoned case fills a batch on its own, so three batches in a row publish nothing.
    publisher(broker, 4).publishPendingCaseEvents();

    assertThat(broker.sentIds()).filteredOn(poisonedIds::contains).hasSize(3);
    assertThat(publishedIds()).containsExactlyInAnyOrderElementsOf(healthyIds);
  }

  @Test
  void abortsRunWhenABatchWorthOfSendsInARowFail() {
    List<Long> cases = new ArrayList<>();
    for (long i = 0; i < 10; i++) {
      cases.add(4000000000000000L + i);
    }
    seedCases(cases.toArray(Long[]::new));
    cases.forEach(this::enqueue);

    var broker = new RecordingJmsTemplate(id -> true);
    publisher(broker, 3).publishPendingCaseEvents();

    // One batch of failed sends, rather than one attempt for every pending case.
    assertThat(broker.sentIds()).hasSize(3);
    assertThat(publishedIds()).isEmpty();
  }

  @Test
  void concurrentPublishersSendEachCaseInOrderWithoutOverlap() throws Exception {
    List<Long> cases = new ArrayList<>();
    for (long i = 0; i < 10; i++) {
      cases.add(2000000000000000L + i);
    }
    seedCases(cases.toArray(Long[]::new));

    var broker = new RecordingJmsTemplate(id -> false);
    var executor = Executors.newFixedThreadPool(5);
    try {
      Future<?> producer = executor.submit(() -> {
        for (int round = 0; round < 20; round++) {
          for (long reference : cases) {
            enqueue(reference);
          }
        }
      });
      List<Future<?>> publishers = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        var publisher = publisher(broker, 7);
        publishers.add(executor.submit(() -> {
          while (!producer.isDone() || !unpublishedIds().isEmpty()) {
            publisher.publishPendingCaseEvents();
          }
        }));
      }
      producer.get();
      for (Future<?> publisher : publishers) {
        publisher.get();
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(broker.overlappingSends).isEmpty();
    assertThat(broker.sentIds()).doesNotHaveDuplicates().hasSize(200);
    Map<Long, List<Long>> sentByCase = broker.sends.stream().collect(Collectors.groupingBy(
        Send::reference, Collectors.mapping(Send::id, Collectors.toList())));
    sentByCase.values().forEach(ids -> assertThat(ids).isSorted());
    assertThat(unpublishedIds()).isEmpty();
  }

  private CcdCaseEventPublisher publisher(JmsTemplate broker, int batchSize) {
    var properties = new CcdServiceBusProperties();
    properties.setDestination("ccd-case-events-test");
    properties.setBatchSize(batchSize);
    return new CcdCaseEventPublisher(repository, broker, properties, transactionManager);
  }

  private void seedCases(Long... references) {
    for (long reference : references) {
      jdbc.update("""
          insert into ccd.case_data (id, reference, version, jurisdiction, case_type_id, state, data,
            supplementary_data, security_classification, case_revision, created_date, last_modified,
            last_state_modified_date)
          values (?, ?, 1, 'TEST', 'TestCase', 'Submitted', '{}'::jsonb, '{}'::jsonb, 'PUBLIC', 1,
            now(), now(), now())
          """, reference % 1_000_000_000L, reference);
    }
  }

  private long enqueue(long reference) {
    return new TransactionTemplate(transactionManager).execute(status -> {
      Long id = jdbc.queryForObject("""
          insert into ccd.message_queue_candidates (reference, message_type, message_information)
          values (?, 'CASE_EVENT', '{}'::jsonb)
          returning id
          """, Long.class, reference);
      jdbc.update("""
          update ccd.message_queue_candidates
             set message_information = jsonb_build_object('CaseId', reference::text, 'QueueId', id)
           where id = ?
          """, id);
      return id;
    });
  }

  private List<Long> publishedIds() {
    return jdbc.queryForList(
        "select id from ccd.message_queue_candidates where published is not null", Long.class);
  }

  private List<Long> unpublishedIds() {
    return jdbc.queryForList(
        "select id from ccd.message_queue_candidates where published is null", Long.class);
  }

  private record Send(long id, long reference) { }

  private static class RecordingJmsTemplate extends JmsTemplate {

    private final Predicate<Long> failing;
    private final List<Send> sends = Collections.synchronizedList(new ArrayList<>());
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Long> overlappingSends = ConcurrentHashMap.newKeySet();

    RecordingJmsTemplate(Predicate<Long> failing) {
      this.failing = failing;
    }

    @Override
    public void convertAndSend(String destination, Object message, MessagePostProcessor postProcessor) {
      JsonNode payload = (JsonNode) message;
      send(payload.get("QueueId").asLong(), payload.get("CaseId").asLong());
    }

    List<Long> sentIds() {
      return sends.stream().map(Send::id).toList();
    }

    private void send(long id, long reference) {
      if (!inFlight.add(reference)) {
        overlappingSends.add(reference);
      }
      try {
        sends.add(new Send(id, reference));
        // Uneven latency lets publishers overtake each other, as they would with a real broker.
        Thread.sleep(ThreadLocalRandom.current().nextInt(4));
        if (failing.test(id)) {
          throw new IllegalStateException("Broker unavailable for message " + id);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } finally {
        inFlight.remove(reference);
      }
    }
  }

  @Configuration
  @Import(CcdMessageQueueRepository.class)
  @ImportAutoConfiguration({
      DecentralisedFlywayAutoConfiguration.class,
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
