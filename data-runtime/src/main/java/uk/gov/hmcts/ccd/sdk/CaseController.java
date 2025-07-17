package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import net.jodah.typetools.TypeResolver;


import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping(path = "/ccd")
public class CaseController {

  private final JdbcTemplate db;
  private final NamedParameterJdbcTemplate ndb;

  private final TransactionTemplate transactionTemplate;

  private final ObjectMapper defaultMapper;
  private final ObjectMapper filteredMapper;
  private final CCDEventListener eventListener;

  private final CaseRepository caseRepository;
  private final ObjectMapper getMapper;
  private final Class caseDataType;
  private final IdempotencyEnforcer idempotencyEnforcer;

  @Autowired
  public CaseController(JdbcTemplate db,
                        TransactionTemplate transactionTemplate,
                        NamedParameterJdbcTemplate ndb,
                        CaseRepository<?> caseRepository,
                        CCDEventListener eventListener,
                        ObjectMapper mapper,
                        IdempotencyEnforcer idempotencyEnforcer,
                        @Qualifier("getMapper") ObjectMapper getMapper) {
    this.db = db;
    this.ndb = ndb;
    this.transactionTemplate = transactionTemplate;
    this.caseRepository = caseRepository;
    this.defaultMapper = mapper;
    this.eventListener = eventListener;
    this.idempotencyEnforcer = idempotencyEnforcer;
    this.filteredMapper = mapper.copy().setAnnotationIntrospector(new FilterExternalFieldsInspector());
    this.getMapper = getMapper;
    Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CaseRepository.class, caseRepository.getClass());
    this.caseDataType = typeArgs[0];
  }

  @GetMapping(
      value = "/cases", // Mapped to the root /cases endpoint
      produces = "application/json"
  )
  @SneakyThrows
  public List<Map<String, Object>> getCases(@RequestParam("case-refs") List<Long> caseRefs) {
    var params = Map.of("caseRefs", caseRefs);

    var results = ndb.queryForList(
        """
        select
              reference as id,
              -- Format timestamp in iso 8601
              to_json(c.created_date)#>>'{}' as created_date,
              jurisdiction,
              case_type_id,
              state,
              data::text as case_data,
              security_classification::text,
              version,
              to_json(last_state_modified_date)#>>'{}' as last_state_modified_date,
              to_json(coalesce(c.last_modified, c.created_date))#>>'{}' as last_modified,
              supplementary_data::text
         from ccd.case_data c
         where reference IN (:caseRefs) -- Use IN (:paramName) for list binding
        """, params);

    // Process each result row in the list
    return results.stream()
        .map(this::processCaseRow)
        .collect(Collectors.toList());
  }

  public Map<String, Object> getCase(Long caseRef) {
    return getCases(List.of(caseRef)).get(0);
  }

    /**
     * Helper method to process a single row of case data from the database.
     * This centralizes the transformation logic for both single and bulk endpoints.
     */
  @SneakyThrows
  private Map<String, Object> processCaseRow(Map<String, Object> row) {
    var result = new HashMap<>(row);

    var data = defaultMapper.readValue((String) result.get("case_data"), caseDataType);
    result.put("case_data", caseRepository.getCase((Long) row.get("id"), data));

    var supplementaryDataJson = row.get("supplementary_data");
    result.put("supplementary_data", defaultMapper.readValue(supplementaryDataJson.toString(), Map.class));

    // Add the empty data_classification map as before
    result.put("data_classification", Map.of());

    return Map.of("case_details", result);
  }

  @PostMapping(
      value = "/cases/{caseRef}/supplementary-data",
      produces = "application/json"
  )
  @SneakyThrows
  public String updateSupplementaryData(@PathVariable("caseRef") long caseRef, @RequestBody SupplementaryDataUpdateRequest request) {
    Map<Long, String> result = Maps.newHashMap();
    request.getRequestData()
        .forEach((operationType, operationSet) -> {
          operationSet.forEach((key, value) -> {
            var path = key.split("\\.");
            log.info("Updating supplementary data for caseRef: {}, operationType: {}, path: {}, value: {}", caseRef, operationType, path, value);
            var updatedValue = ndb.queryForObject(
                """
                    UPDATE ccd.case_data SET supplementary_data = jsonb_set_lax(
                            -- Create the top level entry as a map if it doesn't exist.
                            jsonb_set(supplementary_data, (:path)[ 1 : 1 ], coalesce(supplementary_data #> (:path)[1 : 1], '{}')::jsonb),
                            :path,
                            (
                              case
                                  when :op = '$inc' then (coalesce((supplementary_data #> :path)::integer, 0) + (:value)::integer)::text::jsonb
                                  when :op = '$set' then to_jsonb(:value)
                                  else null -- any other operation will raise an exception
                              end
                            ),
                            true,
                            'raise_exception' -- on setting a null value
                        )
                    where reference = :reference
                    returning 
                      jsonb_build_object('supplementary_data', supplementary_data)::text 
                      as supplementary_data
                    """,
                Map.of(
                    "path", path,
                    "value", value,
                    "reference", caseRef,
                    "op", operationType
                )
                ,
                String.class
            );
            result.put(caseRef, updatedValue);
          });
        });
    return result.get(caseRef);
  }

  @SneakyThrows
  @PostMapping("/cases")
  public ResponseEntity<Map<String, Object>> createEvent(
      @RequestBody POCCaseEvent event,
      @RequestHeader HttpHeaders headers) {

    transactionTemplate.execute(status -> {
      if (idempotencyEnforcer.markProcessedReturningIsAlreadyProcessed(
          headers.getFirst(IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER))) {
        // TODO: Do we need to return the exact same response as before or are we ok to include subsequent changes.
        return status;
      }
      var referer = Objects.requireNonNullElse(headers.getFirst(HttpHeaders.REFERER), "");
      URI uri = UriComponentsBuilder.fromUriString(referer).build().toUri();
      var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

      dispatchAboutToSubmit(event, params);
      var id = saveCaseReturningAuditId(event);
      if (eventListener.hasSubmittedCallbackForEvent(event.getEventDetails().getCaseType(),
          event.getEventDetails().getEventId())) {
        enqueueSubmittedCallback(id, event, headers);
      }
      return status;
    });

    var response = getCase((Long) event.getCaseDetails().get("id"));
    return ResponseEntity.ok(response);
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
  private long saveCaseReturningAuditId(POCCaseEvent event) {
    var caseData = defaultMapper.readValue(defaultMapper.writeValueAsString(event.getCaseDetails().get("case_data")),
        caseDataType);

    var state = event.getEventDetails().getStateId() != null
        ? event.getEventDetails().getStateId()
        : event.getCaseDetails().get("state");
    var caseDetails = event.getCaseDetails();
    int version = (int) Optional.ofNullable(event.getCaseDetails().get("version")).orElse(1);
    var data = filteredMapper.writeValueAsString(caseData);
    // Upsert the case - create if it doesn't exist, update if it does.
    var rowsAffected = db.update("""
            insert into ccd.case_data (last_modified, jurisdiction, case_type_id, state, data, reference, security_classification, version)
            values (now(), ?, ?, ?, (?::jsonb), ?, ?::ccd.securityclassification, ?)
            on conflict (reference)
            do update set
                state = excluded.state,
                data = excluded.data,
                security_classification = excluded.security_classification,
                last_modified = now(),
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

    return saveAuditRecord(event, 1);
  }

  @SneakyThrows
  private POCCaseEvent dispatchAboutToSubmit(POCCaseEvent event, MultiValueMap<String, String> urlParams) {
    if (eventListener.hasSubmitHandler(event.getEventDetails().getCaseType(), event.getEventDetails().getEventId())) {
      eventListener.submit(event.getEventDetails().getCaseType(), event.getEventDetails().getEventId(), event,
          urlParams);
    } else if (eventListener.hasAboutToSubmitCallbackForEvent(event.getEventDetails().getCaseType(),
        event.getEventDetails().getEventId())) {
      var req = CallbackRequest.builder()
          .caseDetails(toCaseDetails(event.getCaseDetails()))
          .caseDetailsBefore(toCaseDetails(event.getCaseDetailsBefore()))
          .eventId(event.getEventDetails().getEventId())
          .build();
      var cb = eventListener.aboutToSubmit(req);

      event.getCaseDetails()
          .put("case_data", defaultMapper.readValue(defaultMapper.writeValueAsString(cb.getData()), Map.class));
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
  public String loadHistory(@PathVariable("caseRef") long caseRef) {
    var result = db.queryForObject(
        """
             select jsonb_agg(
               jsonb_build_object('id', id) ||
               jsonb_build_object('case_reference', case_reference) ||
               jsonb_build_object('event', 
                 to_jsonb(e) - 'case_reference' - 'event_id'
                    || jsonb_build_object('id', event_id) -- See AuditEvent superclass
              )
              order by id desc
           )
             from ccd.case_event e
             where case_reference = ?
            """,
        new Object[] {caseRef}, String.class);
    return result;
  }

  @GetMapping(
      value = "/cases/{caseRef}/history/{eventId}",
      produces = "application/json"
  )
  public String loadHistoryEvent(@PathVariable("caseRef") long caseRef, @PathVariable("eventId") long eventId) {
    return  db.queryForObject(
        """
           select jsonb_build_object('id', id) ||
               jsonb_build_object('case_reference', case_reference) ||
               jsonb_build_object('event', 
                 to_jsonb(e) - 'case_reference' - 'event_id'
                    || jsonb_build_object('id', event_id) -- See AuditEvent superclass
              )
              from ccd.case_event e
              where case_reference = ? and id = ?
            """,
        new Object[] {caseRef, eventId}, String.class);
  }

  @SneakyThrows
  private long saveAuditRecord(POCCaseEvent details, int version) {
    var event = details.getEventDetails();
    var currentView = (Map) getCase((Long) details.getCaseDetails().get("id")).get("case_details");
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
