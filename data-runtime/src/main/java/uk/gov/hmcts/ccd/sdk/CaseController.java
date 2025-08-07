package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.typetools.TypeResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedUpdateSupplementaryDataResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping(path = "/ccd-persistence")
public class CaseController {

  private final NamedParameterJdbcTemplate ndb;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper defaultMapper;
  private final ObjectMapper filteredMapper;
  private final CCDEventListener eventListener;
  private final CaseRepository caseRepository;
  private final Class caseDataType;
  private final IdempotencyEnforcer idempotencyEnforcer;
  private final MessagePublisher publisher;
  private final IdamService idam;

  @Autowired
  public CaseController(TransactionTemplate transactionTemplate,
                        NamedParameterJdbcTemplate ndb,
                        CaseRepository<?> caseRepository,
                        CCDEventListener eventListener,
                        ObjectMapper mapper,
                        IdempotencyEnforcer idempotencyEnforcer,
                        MessagePublisher publisher,
                        IdamService idam) {
    this.ndb = ndb;
    this.transactionTemplate = transactionTemplate;
    this.caseRepository = caseRepository;
    this.defaultMapper = mapper;
    this.eventListener = eventListener;
    this.idempotencyEnforcer = idempotencyEnforcer;
    this.filteredMapper = mapper.copy().setAnnotationIntrospector(new FilterExternalFieldsInspector());
    Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CaseRepository.class, caseRepository.getClass());
    this.caseDataType = typeArgs[0];
    this.publisher = publisher;
    this.idam = idam;
  }

  @GetMapping(
      value = "/cases", // Mapped to the root /cases endpoint
      produces = "application/json"
  )
  @SneakyThrows
  public List<DecentralisedCaseDetails> getCases(@RequestParam("case-refs") List<Long> caseRefs) {
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
              supplementary_data::text,
              coalesce(most_recent_event.id, 2) as global_version
         from ccd.case_data c
             left join lateral (
               select ce.id from ccd.case_event ce where ce.case_reference = c.reference
               order by ce.id DESC limit 1
             ) as most_recent_event on true
         where reference IN (:caseRefs) -- Use IN (:paramName) for list binding
        """, params);

    // Process each result row in the list
    return results.stream()
        .map(this::processCaseRow)
        .collect(Collectors.toList());
  }

  public DecentralisedCaseDetails getCase(Long caseRef) {
    return getCases(List.of(caseRef)).get(0);
  }

  /**
   * Helper method to process a single row of case data from the database.
   * This centralizes the transformation logic for both single and bulk endpoints.
   */
  @SneakyThrows
  private DecentralisedCaseDetails processCaseRow(Map<String, Object> row) {
    var result = new HashMap<>(row);

    var data = defaultMapper.readValue((String) result.get("case_data"), caseDataType);
    result.put("case_data", caseRepository.getCase((Long) row.get("id"), data));

    var supplementaryDataJson = row.get("supplementary_data");
    result.put("supplementary_data", defaultMapper.readValue(supplementaryDataJson.toString(), Map.class));

    result.put("data_classification", Map.of());

    var response = new DecentralisedCaseDetails();
    response.setVersion((Long) result.remove("global_version"));
    response.setCaseDetails(defaultMapper.convertValue(result, uk.gov.hmcts.ccd.domain.model.definition.CaseDetails.class));
    return response;
  }

  @PostMapping(
      value = "/cases/{caseRef}/supplementary-data",
      produces = "application/json"
  )
  @SneakyThrows
  @Transactional
  public DecentralisedUpdateSupplementaryDataResponse updateSupplementaryData(@PathVariable("caseRef") long caseRef, @RequestBody SupplementaryDataUpdateRequest request) {
    final AtomicReference<String> result = new AtomicReference<>();
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
                      supplementary_data::text 
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
            result.set(updatedValue);
          });
        });

    var response = new DecentralisedUpdateSupplementaryDataResponse();
    response.setSupplementaryData(defaultMapper.readTree(result.get()));
    return response;
  }

  @SneakyThrows
  @PostMapping("/cases")
  public ResponseEntity<Map> createEvent(
      @RequestBody DecentralisedCaseEvent event,
      @RequestHeader HttpHeaders headers) {

    var referer = Objects.requireNonNullElse(headers.getFirst(HttpHeaders.REFERER), "");
    URI uri = UriComponentsBuilder.fromUriString(referer).build().toUri();
    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

    var user = idam.retrieveUser(headers.getFirst("Authorization"));
    transactionTemplate.execute(status -> {
      if (idempotencyEnforcer.markProcessedReturningIsAlreadyProcessed(
          headers.getFirst(IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER))) {
        // TODO: Do we need to return the exact same response as before or are we ok to include subsequent changes.
        return status;
      }

      dispatchAboutToSubmit(event, params);
      saveCaseReturningAuditId(event, user);
      return status;
    });

    if (eventListener.hasSubmittedCallbackForEvent(event.getEventDetails().getCaseType(),
        event.getEventDetails().getEventId())) {
      dispatchSubmitted(event);
    }

    var details = getCase(event.getCaseDetails().getReference());
    var response = Map.of("case_details", details);
    return ResponseEntity.ok(response);
  }

  @SneakyThrows
  private long saveCaseReturningAuditId(DecentralisedCaseEvent event, IdamService.User user) {
    var caseData = defaultMapper.convertValue(event.getCaseDetails().getData(), caseDataType);

    var state = event.getCaseDetails().getState();
    var caseDetails = event.getCaseDetails();
    int version = Optional.ofNullable(event.getCaseDetails().getVersion()).orElse(1);
    var data = filteredMapper.writeValueAsString(caseData);
    var oldState = event.getCaseDetailsBefore() != null
        ? event.getCaseDetailsBefore().getState()
        : null;

    // Upsert the case - create if it doesn't exist, update if it does.
    var sql = """
            insert into ccd.case_data (last_modified, jurisdiction, case_type_id, state, data, reference, security_classification, version)
            values (now(), :jurisdiction, :case_type_id, :state, (:data::jsonb), :reference, :security_classification::ccd.securityclassification, :version)
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
            """;
    var params = Map.of(
        "jurisdiction", caseDetails.getJurisdiction(),
        "case_type_id", caseDetails.getCaseTypeId(),
        "state", state,
        "data", data,
        "reference", caseDetails.getReference(),
        "security_classification", caseDetails.getSecurityClassification().toString(),
        "version", version
    );

    var rowsAffected = ndb.update(sql, params);
    if (rowsAffected != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently");
    }

    return saveAuditRecord(event, oldState, user);
  }

  @SneakyThrows
  private void dispatchSubmitted(DecentralisedCaseEvent event) {
    if (eventListener.hasSubmittedCallbackForEvent(event.getEventDetails().getCaseType(),
        event.getEventDetails().getEventId())) {
      var req = CallbackRequest.builder()
          .caseDetails(toCaseDetails(event.getCaseDetails()))
          .caseDetailsBefore(toCaseDetails(event.getCaseDetailsBefore()))
          .eventId(event.getEventDetails().getEventId())
          .build();
      eventListener.submitted(req);
    }
  }

  @SneakyThrows
  private DecentralisedCaseEvent dispatchAboutToSubmit(DecentralisedCaseEvent event, MultiValueMap<String, String> urlParams) {
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
          .setData(defaultMapper.convertValue(cb.getData(), Map.class));
    }
    return event;
  }

  @GetMapping(
      value = "/cases/{caseRef}/history",
      produces = "application/json"
  )
  public String loadHistory(@PathVariable("caseRef") long caseRef) {
    return ndb.queryForObject(
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
             where case_reference = :caseRef
            """,
        Map.of("caseRef", caseRef), String.class);
  }

  @GetMapping(
      value = "/cases/{caseRef}/history/{eventId}",
      produces = "application/json"
  )
  public String loadHistoryEvent(@PathVariable("caseRef") long caseRef, @PathVariable("eventId") long eventId) {
    return  ndb.queryForObject(
        """
           select jsonb_build_object('id', id) ||
               jsonb_build_object('case_reference', case_reference) ||
               jsonb_build_object('event',
                 to_jsonb(e) - 'case_reference' - 'event_id'
                    || jsonb_build_object('id', event_id) -- See AuditEvent superclass
              )
              from ccd.case_event e
              where case_reference = :caseRef and id = :eventId
            """,
        Map.of("caseRef", caseRef, "eventId", eventId), String.class);
  }

  @SneakyThrows
  private long saveAuditRecord(DecentralisedCaseEvent details, String oldState, IdamService.User user) {
    var event = details.getEventDetails();
    var currentView = getCase(details.getCaseDetails().getReference()).getCaseDetails();
    var sql = """
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
            values (:data::jsonb, :event_id, :user_id, :case_reference, :case_type_id, :case_type_version, :state_id, :user_first_name, :user_last_name, :event_name, :state_name, :summary, :description, :security_classification::ccd.securityclassification)
            returning id, created_date
            """;

    var params = new HashMap<String, Object>();
    params.put("data", defaultMapper.writeValueAsString(currentView.getData()));
    params.put("event_id", event.getEventId());
    params.put("user_id", user.getUserDetails().getUid());
    params.put("case_reference", currentView.getReference());
    params.put("case_type_id", event.getCaseType());
    params.put("case_type_version", 1); // TODO: do we need to track definition version if it is our definition?
    params.put("state_id", currentView.getState());
    params.put("user_first_name", user.getUserDetails().getGivenName());
    params.put("user_last_name", user.getUserDetails().getFamilyName());
    params.put("event_name", event.getEventName());
    params.put("state_name", eventListener.nameForState(details.getEventDetails().getCaseType(), String.valueOf(currentView.getState())));
    params.put("summary", event.getSummary());
    params.put("description", event.getDescription());
    params.put("security_classification", currentView.getSecurityClassification().toString());

    var result = ndb.queryForMap(sql, params);
    var eventId = (long) result.get("id");
    var timestamp = ((java.sql.Timestamp) result.get("created_date")).toLocalDateTime();
    this.publisher.publishEvent(
        currentView.getReference(),
        user.getUserDetails().getUid(),
        event.getEventId(),
        oldState,
        toCaseDetails(details.getCaseDetails()),
        eventId,
        timestamp
    );
    return eventId;
  }

  private CaseDetails toCaseDetails(uk.gov.hmcts.ccd.domain.model.definition.CaseDetails data) {
    return defaultMapper.convertValue(data, CaseDetails.class);
  }

}
