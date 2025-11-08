package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;

/**
 * Central place for loading {@link DecentralisedCaseDetails} with the
 * application-provided projection applied. Today this delegates to
 * {@link CaseDataRepository} for persistence, but the orchestration lives here so
 * callers stay agnostic of how the raw data is sourced.
 */
@Service
class CaseProjectionService {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  @SuppressWarnings("rawtypes")
  private final CaseView caseView;
  private final CaseDataRepository caseDataRepository;
  private final ObjectMapper mapper;
  private final Class<?> caseDataType;
  private final Class<? extends Enum<?>> stateType;

  CaseProjectionService(CaseDataRepository caseDataRepository,
                        ObjectMapper mapper,
                        CaseView<?, ?> caseView) {
    this.caseDataRepository = caseDataRepository;
    this.mapper = mapper;
    this.caseView = caseView;

    Class<?> userClass = ClassUtils.getUserClass(caseView);
    ResolvableType caseViewType = ResolvableType.forClass(userClass).as(CaseView.class);
    Class<?> resolvedCase = caseViewType.getGeneric(0).resolve();
    this.caseDataType = resolvedCase != null ? resolvedCase : Map.class;
    Class<?> resolvedState = caseViewType.getGeneric(1).resolve();
    if (resolvedState == null || !Enum.class.isAssignableFrom(resolvedState)) {
      throw new IllegalStateException(
          "CaseView implementations must declare an enum state type. Found: %s".formatted(resolvedState));
    }
    @SuppressWarnings("unchecked")
    Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) resolvedState;
    this.stateType = enumClass;
  }

  DecentralisedCaseDetails load(long caseRef) {
    DecentralisedCaseDetails raw = caseDataRepository.getCase(caseRef);
    return applyProjection(raw);
  }

  List<DecentralisedCaseDetails> load(List<Long> caseRefs) {
    return caseDataRepository.getCases(caseRefs).stream()
        .map(this::applyProjection)
        .collect(Collectors.toList());
  }

  private DecentralisedCaseDetails applyProjection(DecentralisedCaseDetails raw) {
    var caseDetails = raw.getCaseDetails();
    long reference = caseDetails.getReference();
    String state = caseDetails.getState();

    Object blobCase = mapper.convertValue(caseDetails.getData(), caseDataType);
    Enum<?> typedState = Enum.valueOf((Class<? extends Enum>) stateType, state);

    @SuppressWarnings("rawtypes")
    CaseViewRequest request = new CaseViewRequest(reference, typedState);
    Object projected = caseView.getCase(request, blobCase);
    Map<String, JsonNode> serialised = mapper.convertValue(projected, JSON_NODE_MAP);
    caseDetails.setData(serialised);
    return raw;
  }
}
