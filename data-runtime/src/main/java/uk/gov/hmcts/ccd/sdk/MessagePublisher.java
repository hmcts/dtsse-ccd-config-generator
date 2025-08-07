package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class MessagePublisher {

    private DefinitionBlockGenerator definitionBlockGenerator;
    private final DataBlockGenerator dataBlockGenerator;
    private Map<String, CaseTypeDefinition> definitions = Map.of();
    private final ObjectMapper mapper;
    private final NamedParameterJdbcTemplate db;

    @SneakyThrows
    public MessagePublisher(
        MessagingProperties messagingProperties,
                            ObjectMapper mapper,
                            NamedParameterJdbcTemplate db) {
            this.definitionBlockGenerator = new DefinitionBlockGenerator(messagingProperties);
            this.dataBlockGenerator = new DataBlockGenerator();
            this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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
    public void publishEvent(long caseReference, String userId, String eventId, String oldState, CaseDetails caseDetails, long instanceId, LocalDateTime timestamp) {
        var caseType = loadDefinitions().get(caseDetails.getCaseTypeId());
        if (null == caseType) {
            log.error("Case type {} is not known", eventId);
            return;
        }

        CaseEventDefinition caseEventDefinition = caseType.findCaseEvent(eventId).orElseThrow();
        if (!caseEventDefinition.getPublish()) {
            log.info("Event {} is not marked for publishing, skipping message publication", eventId);
            return;
        }

        log.info("Publishing event {} for case {}", eventId, caseReference);
        var info = populateMessageInformation(instanceId, timestamp, caseReference, userId, caseDetails, caseType,
            caseEventDefinition, oldState);

        // Convert the MessageInformation object to a JSON string
        String messageInformationJson = mapper.writeValueAsString(info);

        final String SQL = "insert into ccd.message_queue_candidates (message_type, time_stamp, message_information) " +
            "values (:messageType, :timestamp, :messageInformation::jsonb)";

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("messageType", "CASE_EVENT");
        params.addValue("timestamp", timestamp);
        params.addValue("messageInformation", messageInformationJson);

        db.update(SQL, params);
        log.info("Successfully published event {} for case {} to message_queue_candidates", eventId, caseReference);
    }

    @SneakyThrows
    MessageInformation populateMessageInformation(long instanceId, LocalDateTime timestamp, long caseReference, String userId, CaseDetails caseDetails,
                                                  CaseTypeDefinition caseType, CaseEventDefinition caseEventDefinition, String oldState) {

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

        var ccdDetails = mapper.readValue(mapper.writeValueAsString(caseDetails), uk.gov.hmcts.ccd.domain.model.definition.CaseDetails.class);
        AdditionalMessageInformation additionalMessageInformation = new AdditionalMessageInformation();
        additionalMessageInformation.setData(dataBlockGenerator
            .generateData(new AdditionalDataContext(caseEventDefinition, caseType, ccdDetails)));
        additionalMessageInformation.setDefinition(definitionBlockGenerator
            .generateDefinition(new AdditionalDataContext(caseEventDefinition, caseType, ccdDetails)));
        messageInformation.setData(additionalMessageInformation);

        return messageInformation;
    }
}
