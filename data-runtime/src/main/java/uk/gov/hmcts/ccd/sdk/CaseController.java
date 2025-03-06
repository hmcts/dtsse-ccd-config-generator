package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.util.StringUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import net.jodah.typetools.TypeResolver;


import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping(path = "/ccd")
public class CaseController {

    private final JdbcTemplate db;

    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper defaultMapper;
    private final ObjectMapper filteredMapper;
    private final CCDEventListener eventListener;

    private final CaseRepository caseRepository;
    private final ObjectMapper getMapper;
    private final Class caseDataType;

    @Autowired
    public CaseController(JdbcTemplate db,
                          TransactionTemplate transactionTemplate,
                          CaseRepository<?> caseRepository,
                          CCDEventListener eventListener,
                          ObjectMapper mapper,
                          @Qualifier("getMapper") ObjectMapper getMapper) {
        this.db = db;
        this.transactionTemplate = transactionTemplate;
        this.caseRepository = caseRepository;
        this.defaultMapper = mapper;
        this.eventListener = eventListener;
        this.filteredMapper = mapper.copy().setAnnotationIntrospector(new FilterExternalFieldsInspector());
        this.getMapper = getMapper;
        Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CaseRepository.class, caseRepository.getClass());
        this.caseDataType = typeArgs[0];
    }

    @GetMapping(
            value = "/cases/{caseRef}",
            produces = "application/json"
    )
    @SneakyThrows
    public Map<String, Object> getCase(@PathVariable("caseRef") long caseRef, @RequestHeader("roleAssignments") String roleAssignments) {
        log.info("RoleAssignments: {}", decodeHeader(roleAssignments));
        var result = db.queryForMap(
                """
                    select
                          reference as id,
                          -- Format timestamp in iso 8601
                          to_json(c.created_date)#>>'{}' as created_date,
                          jurisdiction,
                          case_type_id,
                          state,
                          data::text as case_data,
                          '{}'::jsonb as data_classification,
                          security_classification::text,
                          version,
                          to_json(last_state_modified_date)#>>'{}' as last_state_modified_date,
                          to_json(coalesce(c.last_modified, c.created_date))#>>'{}' as last_modified,
                          supplementary_data::text
                     from ccd.case_data c
                     where reference = ?
                        """, caseRef);
        var data = defaultMapper.readValue((String) result.get("case_data"), caseDataType);
        result.put("case_data", caseRepository.getCase(caseRef, data, roleAssignments));
        return result;
    }

    @SneakyThrows
    @PostMapping("/cases")
    public ResponseEntity<Map<String, Object>> createEvent(
        @RequestBody POCCaseEvent event,
        @RequestHeader HttpHeaders headers) {
        log.info("case Details: {}", event);
        String roleAssignments = headers.get("roleAssignments").get(0);
        log.info("roleAssignments size: {}", roleAssignments.getBytes().length);
        log.info("RoleAssignments: {}", decodeHeader(roleAssignments));

        transactionTemplate.execute( status -> {
                dispatchAboutToSubmit(event);
                var id = saveCaseReturningAuditId(event, roleAssignments);
                if (eventListener.hasSubmittedCallbackForEvent(event.getEventDetails().getCaseType(), event.getEventDetails().getEventId())) {
                    enqueueSubmittedCallback(id, event, headers);
                }
                return status;
        });

        var response = getCase((Long) event.getCaseDetails().get("id"), roleAssignments);
        log.info("case response: {}", response);
        return ResponseEntity.ok(response);
    }

  @SneakyThrows
  private List<CaseAssignedUserRole> decodeHeader(String roles) throws JsonProcessingException {
    if (StringUtils.isBlank(roles)) {
      return List.of();
    }
    log.info("roles: {}", roles);

    String roleAssignments = new String(Base64.getDecoder().decode(roles));
    log.info("roleAssignments: {}", roleAssignments);

    return getMapper.readValue(roleAssignments, new TypeReference<List<CaseAssignedUserRole>>() {
    });
  }

    @SneakyThrows
    private void enqueueSubmittedCallback(long auditEventId, POCCaseEvent event, HttpHeaders headers) {
        var req = CallbackRequest.builder()
            .caseDetails(toCaseDetails(event.getCaseDetails()))
            .caseDetailsBefore(toCaseDetails(event.getCaseDetailsBefore()))
            .eventId(event.getEventDetails().getEventId())
            .build();

        db.update(
            """
            insert into ccd.submitted_callback_queue (case_event_id, event_id, payload, headers)
            values (?, ?, ?::jsonb, ?::jsonb)
            """,
            auditEventId,
            event.getEventDetails().getEventId(),
            defaultMapper.writeValueAsString(req),
            defaultMapper.writeValueAsString(headers.toSingleValueMap())
        );
    }

    @SneakyThrows
    private long saveCaseReturningAuditId(POCCaseEvent event, String roleAssignments) {
        var caseData = defaultMapper.readValue(defaultMapper.writeValueAsString(event.getCaseDetails().get("case_data")), caseDataType);

        var state = event.getEventDetails().getStateId() != null
            ? event.getEventDetails().getStateId()
            : event.getCaseDetails().get("state");
        var caseDetails = event.getCaseDetails();
        int version = (int) Optional.ofNullable(event.getCaseDetails().get("version")).orElse(1);
        var data = filteredMapper.writeValueAsString(caseData);
        // Upsert the case - create if it doesn't exist, update if it does.
        var rowsAffected = db.update( """
                insert into ccd.case_data (last_modified, jurisdiction, case_type_id, state, data, reference, security_classification, version)
                values (now(), ?, ?, ?, (?::jsonb), ?, ?::ccd.securityclassification, ?)
                on conflict (reference)
                do update set
                    state = excluded.state,
                    data = excluded.data,
                    security_classification = excluded.security_classification,
                    last_modified = now(),
                    supplementary_data = excluded.supplementary_data,
                    resolved_ttl = excluded.resolved_ttl,
                    version = case
                                when case_data.data is distinct from excluded.data then case_data.version + 1
                                else case_data.version
                              end,
                    last_state_modified_date = case
                                                 when case_data.state is distinct from excluded.state then now()
                                                 else case_data.last_state_modified_date
                                               end
                    WHERE case_data.version = EXCLUDED.version;
                    """,
            caseDetails.get("jurisdiction"),
            caseDetails.get("case_type_id"),
            state,
            data,
            caseDetails.get("id"),
            caseDetails.get("security_classification"),
            version
        );
        if (rowsAffected != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently");
        }

        return saveAuditRecord(event, 1, roleAssignments);
    }

    @SneakyThrows
    private POCCaseEvent dispatchAboutToSubmit(POCCaseEvent event) {
      if (eventListener.hasAboutToSubmitCallbackForEvent(event.getEventDetails().getCaseType(), event.getEventDetails().getEventId())) {
            var req = CallbackRequest.builder()
                .caseDetails(toCaseDetails(event.getCaseDetails()))
                .caseDetailsBefore(toCaseDetails(event.getCaseDetailsBefore()))
                .eventId(event.getEventDetails().getEventId())
                .build();
            var cb = eventListener.aboutToSubmit(req);

            event.getCaseDetails().put("case_data", defaultMapper.readValue(defaultMapper.writeValueAsString(cb.getData()), Map.class));
            if (cb.getState() != null) {
                event.getEventDetails().setStateId(cb.getState().toString());
            }
      }
        return event;
    }

    @GetMapping(
            value = "/cases/{caseRef}/history",
            produces = "application/json"
    )
    public String loadHistory(@PathVariable("caseRef") long caseRef, @RequestHeader("roleAssignments") String roleAssignments) {
        return db.queryForObject(
                """
                         select jsonb_agg(to_jsonb(e) - 'case_reference' - 'event_id'
                         || jsonb_build_object('case_data_id', case_reference)
                         || jsonb_build_object('event_instance_id', id)
                         || jsonb_build_object('id', event_id)
                          order by id desc)
                         from ccd.case_event e
                         where case_reference = ?
                        """,
                new Object[]{caseRef}, String.class);
    }

    @SneakyThrows
    private long saveAuditRecord(POCCaseEvent details, int version, String roleAssignments) {
        var event = details.getEventDetails();
        var currentView = getCase((Long) details.getCaseDetails().get("id"), roleAssignments);
        var result = db.queryForMap(
                """
                        insert into ccd.case_event (
                          data,
                          event_id,
                          user_id,
                          case_reference,
                          case_type_id,
                          case_type_version,
                          state_id,
                          user_first_name,
                          user_last_name,
                          event_name,
                          state_name,
                          summary,
                          description,
                          security_classification)
                        values (?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?::ccd.securityclassification)
                        returning id
                        """,
         defaultMapper.writeValueAsString(currentView.get("case_data")),
                event.getEventId(),
                "user-id",
                currentView.get("id"),
                "NFD",
                version,
                currentView.get("state"),
                "a-first-name",
                "a-last-name",
                event.getEventName(),
                eventListener.nameForState(details.getEventDetails().getCaseType(), String.valueOf(currentView.get("state"))),
                event.getSummary(),
                event.getDescription(),
                currentView.get("security_classification")
        );
        return (long) result.get("id");
    }
    @SneakyThrows
    private CaseDetails toCaseDetails(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        return defaultMapper.readValue(defaultMapper.writeValueAsString(data), CaseDetails.class);
    }
}
