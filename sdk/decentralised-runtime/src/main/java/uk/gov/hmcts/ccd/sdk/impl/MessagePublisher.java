package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.config.MessagingProperties;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEventDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;
import uk.gov.hmcts.ccd.domain.model.std.AdditionalMessageInformation;
import uk.gov.hmcts.ccd.domain.model.std.MessageInformation;
import uk.gov.hmcts.ccd.domain.service.message.additionaldata.AdditionalDataContext;
import uk.gov.hmcts.ccd.domain.service.message.additionaldata.DataBlockGenerator;
import uk.gov.hmcts.ccd.domain.service.message.additionaldata.DefinitionBlockGenerator;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

@Slf4j
@Service
@ConditionalOnBean(MessagingProperties.class)
class MessagePublisher {

  private final DefinitionBlockGenerator definitionBlockGenerator;
  private final DataBlockGenerator dataBlockGenerator;
  private Map<String, CaseTypeDefinition> definitions = Map.of();
  private final ObjectMapper mapper;
  private final NamedParameterJdbcTemplate db;

  @SneakyThrows
  public MessagePublisher(
      MessagingProperties messagingProperties,
      NamedParameterJdbcTemplate db) {
    this.definitionBlockGenerator = new DefinitionBlockGenerator(messagingProperties);
    this.dataBlockGenerator = new DataBlockGenerator();
    this.mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES))
        .registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.db = db;
    this.definitions = loadDefinitions();
  }

  @SneakyThrows
  private synchronized Map<String, CaseTypeDefinition> loadDefinitions() {
    if (!this.definitions.isEmpty()) {
      return this.definitions;
    }
    this.definitions = new HashMap<>();
    File[] jsonFiles = new File("build/cftlib/definition-snapshots").listFiles((dir, name) -> name.endsWith(".json"));

    if (jsonFiles != null) {
      for (File file : jsonFiles) {
        // Use filename without extension as the key
        String fileNameWithoutExtension = file.getName().substring(0, file.getName().lastIndexOf("."));
        CaseTypeDefinition definition = mapper.readValue(file, CaseTypeDefinition.class);
        definitions.put(fileNameWithoutExtension, definition);
      }
    }
    return definitions;
  }

  @SneakyThrows
  public void publishEvent(
      long caseReference,
      String userId,
      String eventId,
      String oldState,
      CaseDetails caseDetails,
      long instanceId,
      LocalDateTime timestamp
  ) {
    var caseType = loadDefinitions().get(caseDetails.getCaseTypeId());
    if (null == caseType) {
      log.error("Case type {} is not known", caseDetails.getCaseTypeId());
      return;
    }

    Optional<CaseEventDefinition> opt = caseType.findCaseEvent(eventId);
    if (opt.isEmpty() || !opt.get().getPublish()) {
      log.info("Event {} is not marked for publishing, skipping message publication", eventId);
      return;
    }

    log.info("Publishing event {} for case {}", eventId, caseReference);
    var info = populateMessageInformation(
        instanceId,
        timestamp,
        caseReference,
        userId,
        caseDetails,
        caseType,
        opt.get(),
        oldState
    );

    // Convert the MessageInformation object to a JSON string
    String messageInformationJson = mapper.writeValueAsString(info);

    final String SQL = """
        insert into ccd.message_queue_candidates (reference, message_type, time_stamp, message_information)
        values (:caseReference, :messageType, :timestamp, :messageInformation::jsonb)
        """;

    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("caseReference", caseReference);
    params.addValue("messageType", "CASE_EVENT");
    params.addValue("timestamp", timestamp);
    params.addValue("messageInformation", messageInformationJson);

    db.update(SQL, params);
    log.info("Successfully published event {} for case {} to message_queue_candidates", eventId, caseReference);
  }

  @SneakyThrows
  MessageInformation populateMessageInformation(
      long instanceId,
      LocalDateTime timestamp,
      long caseReference,
      String userId,
      CaseDetails caseDetails,
      CaseTypeDefinition caseType,
      CaseEventDefinition caseEventDefinition,
      String oldState
  ) {

    final MessageInformation messageInformation = new MessageInformation();

    messageInformation.setCaseId(String.valueOf(caseReference));
    messageInformation.setJurisdictionId(caseDetails.getJurisdiction());
    messageInformation.setCaseTypeId(caseDetails.getCaseTypeId());
    messageInformation.setEventInstanceId(instanceId);
    messageInformation.setEventTimestamp(timestamp);
    messageInformation.setEventId(caseEventDefinition.getId());
    messageInformation.setUserId(userId);
    messageInformation.setPreviousStateId(oldState);
    messageInformation.setNewStateId(caseDetails.getState());

    var ccdDetails = mapper.readValue(
        mapper.writeValueAsString(caseDetails),
        uk.gov.hmcts.ccd.domain.model.definition.CaseDetails.class
    );
    AdditionalMessageInformation additionalMessageInformation = new AdditionalMessageInformation();
    additionalMessageInformation.setData(
        dataBlockGenerator.generateData(new AdditionalDataContext(caseEventDefinition, caseType, ccdDetails))
    );
    additionalMessageInformation.setDefinition(
        definitionBlockGenerator.generateDefinition(
            new AdditionalDataContext(caseEventDefinition, caseType, ccdDetails)
        )
    );
    messageInformation.setData(additionalMessageInformation);

    return messageInformation;
  }
}
