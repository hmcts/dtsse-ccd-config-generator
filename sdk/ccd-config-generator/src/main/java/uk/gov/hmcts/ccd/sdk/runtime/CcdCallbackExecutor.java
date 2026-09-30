package uk.gov.hmcts.ccd.sdk.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.TypedPropertyGetter;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.ccd.sdk.api.external.ClientContext;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalRejection;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartResponse;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalUser;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@Component
public class CcdCallbackExecutor {

  private final ResolvedConfigRegistry registry;
  private final ObjectMapper mapper;
  private final ObjectProvider<ExternalUserResolver> users;
  private final Map<String, JavaType> caseTypeToJavaType = Maps.newHashMap();

  private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");

  @Autowired
  public CcdCallbackExecutor(ResolvedConfigRegistry registry, ObjectMapper mapper,
                             ObjectProvider<ExternalUserResolver> users) {
    this.registry = registry;
    this.mapper = mapper;
    this.users = users;
    for (ResolvedCCDConfig<?, ?, ?> config : registry.getAll()) {
      this.caseTypeToJavaType.put(config.getCaseType(),
          mapper.getTypeFactory().constructParametricType(CaseDetails.class, config.getCaseClass(),
              config.getStateClass()));
    }
  }

  @SneakyThrows
  public AboutToStartOrSubmitResponse aboutToStart(CallbackRequest request, String authorisation,
                                                   String clientContext) {
    log.info("About to start event ID: {}", request.getEventId());

    var event = findCaseEvent(request);

    if (event.hasStartHandler()) {
      Map<String, Object> data = request.getCaseDetails().getData();
      // An external event's start handler loads what it needs itself, so the case is not read here.
      var domainClass = event.isExternal() ? null
          : mapper.convertValue(data, registry.getRequired(request.getCaseDetails().getCaseTypeId()).getCaseClass());
      EventPayload payload = new EventPayload<>(
          request.getCaseDetails().getId(),
          domainClass,
          new LinkedMultiValueMap<>()
      );

      Object response = event.start(payload, event.isExternal() ? externalUser(authorisation) : null,
          clientContext(clientContext));
      if (!event.isExternal()) {
        return AboutToStartOrSubmitResponse.builder().data(response).build();
      }
      return switch ((ExternalStartResponse<?>) response) {
        case ExternalRejection<?> rejected ->
            AboutToStartOrSubmitResponse.builder().errors(rejected.errors()).build();
        case ExternalStartResponse.Started<?> started -> {
          // The payload rides in its own field; the case data goes back to CCD as it came.
          Map<String, Object> withPayload = data == null ? new HashMap<>() : new HashMap<>(data);
          withPayload.put(DecentralisedConfigBuilder.PAYLOAD_FIELD, mapper.writeValueAsString(started.payload()));
          yield AboutToStartOrSubmitResponse.builder().data(withPayload).build();
        }
      };
    }

    return findCallback(request, Event::getAboutToStartCallback)
        .handle(convertCaseDetails(request.getCaseDetails()));
  }

  /**
   * Reads the frontend's context as whatever the start handler asks for, ignoring what it does not
   * name. The context is JSON, which XUI and CCD base64-encode, sometimes inside square brackets.
   */
  private ClientContext clientContext(String header) {
    if (header == null || header.isBlank()) {
      return ClientContext.none();
    }
    String unwrapped = header.startsWith("[") && header.endsWith("]")
        ? header.substring(1, header.length() - 1) : header;
    String json = BASE64.matcher(unwrapped).matches() && unwrapped.length() % 4 == 0
        ? new String(Base64.getDecoder().decode(unwrapped), StandardCharsets.UTF_8) : unwrapped;
    return ClientContext.reading(type -> {
      try {
        return mapper.readerFor(type).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
      } catch (IOException e) {
        throw new IllegalArgumentException("The client context cannot be read as a " + type.getName(), e);
      }
    });
  }

  private ExternalUser externalUser(String authorisation) {
    ExternalUserResolver resolver = users.getIfAvailable();
    if (resolver == null) {
      throw new IllegalStateException("External events need the decentralised runtime to resolve their user");
    }
    return resolver.resolve(authorisation);
  }

  @SneakyThrows
  public AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request) {
    log.info("About to submit event ID: {}", request.getEventId());

    return findCallback(request, Event::getAboutToSubmitCallback)
        .handle(convertCaseDetails(request.getCaseDetails()),
            convertCaseDetails(request.getCaseDetailsBefore(), request.getCaseDetails().getCaseTypeId()));
  }

  @SneakyThrows
  public SubmittedCallbackResponse submitted(CallbackRequest request) {
    log.info("Submitted event ID: {}", request.getEventId());
    return findCallback(request, Event::getSubmittedCallback)
        .handle(convertCaseDetails(request.getCaseDetails()),
            convertCaseDetails(request.getCaseDetailsBefore(), request.getCaseDetails().getCaseTypeId()));
  }

  @SneakyThrows
  public AboutToStartOrSubmitResponse midEvent(CallbackRequest request, String page) {
    log.info("Mid event callback: {} for page {}", request.getEventId(), page);
    MidEvent<?, ?> callback = findCaseEvent(request).getFields().getPagesToMidEvent().get(page);

    if (callback == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for "
          + request.getEventId() + " for page " + page);
    }
    return callback.handle(convertCaseDetails(request.getCaseDetails()),
        convertCaseDetails(request.getCaseDetailsBefore(),
            request.getCaseDetails().getCaseTypeId()));
  }

  private <T> T findCallback(CallbackRequest request, TypedPropertyGetter<Event<?, ?, ?>, T> getter) {
    T result = getter.get(findCaseEvent(request));
    if (result == null) {
      log.warn("No callback for event {}", request.getEventId());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Callback not found: " + request.getEventId());
    }
    return result;
  }

  private Event<?, ?, ?> findCaseEvent(CallbackRequest request) {
    String caseType = request.getCaseDetails().getCaseTypeId();
    var config = registry.find(caseType)
        .orElseThrow(() -> {
          log.warn("No configuration found for case type {}", caseType);
          return new ResponseStatusException(HttpStatus.NOT_FOUND, "Case type not found: " + caseType);
        });

    Event<?, ?, ?> result = config.getEvents().get(request.getEventId());
    if (result == null) {
      log.warn("Unknown event {}", request.getEventId());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case event not found: " + request.getEventId());
    }

    return result;
  }

  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails) {
    return convertCaseDetails(ccdDetails, ccdDetails.getCaseTypeId());
  }

  @SneakyThrows
  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails,
                                 String caseType) {
    if (!caseTypeToJavaType.containsKey(caseType)) {
      log.warn("Handler not found for {}", caseType);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for " + caseType);
    }

    if (ccdDetails != null) {
      try {
        Map<String, Object> migratedData = registry.applyPreEventHooks(caseType, ccdDetails.getData());
        ccdDetails.setData(migratedData);
      } catch (Exception e) {
        log.error("Error running pre-event hooks", e);
      }
    }

    String json = mapper.writeValueAsString(ccdDetails);
    CaseDetails result = mapper.readValue(json, caseTypeToJavaType.get(caseType));
    return result;
  }
}
