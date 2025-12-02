package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;

/**
 * Central place for loading {@link DecentralisedCaseDetails} with the
 * application-provided projection applied. Today this delegates to
 * {@link CaseDataRepository} for persistence, but the orchestration lives here so
 * callers stay agnostic of how the raw data is sourced.
 */
@Service
class CaseProjectionService {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final CaseDataRepository caseDataRepository;
  private final ObjectMapper mapper;
  private final Map<String, CaseViewBinding> bindings;

  CaseProjectionService(CaseDataRepository caseDataRepository,
                        ObjectMapper mapper,
                        List<CaseView<?, ?>> caseViews,
                        ResolvedConfigRegistry configRegistry) {
    this.caseDataRepository = caseDataRepository;
    this.mapper = mapper;
    this.bindings = buildBindings(caseViews, configRegistry.asMap());
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
    String caseTypeId = caseDetails.getCaseTypeId();
    String state = caseDetails.getState();

    CaseViewBinding binding = bindings.get(caseTypeId);
    if (binding == null) {
      throw new IllegalStateException(
          "No CaseView registered for decentralised case type %s".formatted(caseTypeId));
    }

    Object blobCase = mapper.convertValue(caseDetails.getData(), binding.caseDataType());
    Enum<?> typedState = Enum.valueOf((Class<? extends Enum>) binding.stateType(), state);

    @SuppressWarnings("rawtypes")
    CaseViewRequest request = new CaseViewRequest(reference, typedState);
    @SuppressWarnings({"rawtypes", "unchecked"})
    Object projected = ((CaseView) binding.caseView()).getCase(request, blobCase);
    Map<String, JsonNode> serialised = mapper.convertValue(projected, JSON_NODE_MAP);
    caseDetails.setData(serialised);
    return raw;
  }

  private Map<String, CaseViewBinding> buildBindings(List<CaseView<?, ?>> caseViews,
                                                     Map<String, ResolvedCCDConfig<?, ?, ?>> configs) {
    if (caseViews == null || caseViews.isEmpty()) {
      throw new IllegalStateException("At least one CaseView bean is required when running decentralised.");
    }

    Map<String, CaseViewBinding> resolvedBindings = new HashMap<>();

    for (CaseView<?, ?> view : caseViews) {
      Class<?> caseDataType = resolveCaseDataType(view);
      Class<? extends Enum<?>> stateType = resolveStateType(view);
      Set<String> supportedCaseTypes = resolveCaseTypes(view, caseDataType, stateType, configs);

      for (String caseType : supportedCaseTypes) {
        if (!configs.containsKey(caseType)) {
          throw new IllegalStateException(
              "CaseView %s declares unknown case type %s".formatted(view.getClass().getName(), caseType));
        }
        CaseViewBinding previous = resolvedBindings.putIfAbsent(
            caseType,
            new CaseViewBinding(view, caseDataType, stateType)
        );
        if (previous != null) {
          throw new IllegalStateException(
              "Multiple CaseView beans registered for case type %s: %s and %s".formatted(
                  caseType, previous.caseView().getClass().getName(), view.getClass().getName()
              ));
        }
      }
    }

    return Map.copyOf(resolvedBindings);
  }

  private Class<?> resolveCaseDataType(CaseView<?, ?> caseView) {
    Class<?> userClass = ClassUtils.getUserClass(caseView);
    ResolvableType caseViewType = ResolvableType.forClass(userClass).as(CaseView.class);
    Class<?> resolvedCase = caseViewType.getGeneric(0).resolve();
    return resolvedCase != null ? resolvedCase : Map.class;
  }

  private Class<? extends Enum<?>> resolveStateType(CaseView<?, ?> caseView) {
    Class<?> userClass = ClassUtils.getUserClass(caseView);
    ResolvableType caseViewType = ResolvableType.forClass(userClass).as(CaseView.class);
    Class<?> resolvedState = caseViewType.getGeneric(1).resolve();
    if (resolvedState == null || !Enum.class.isAssignableFrom(resolvedState)) {
      throw new IllegalStateException(
          "CaseView implementations must declare an enum state type. Found: %s".formatted(resolvedState));
    }
    @SuppressWarnings("unchecked")
    Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) resolvedState;
    return enumClass;
  }

  private Set<String> resolveCaseTypes(CaseView<?, ?> view,
                                       Class<?> caseDataType,
                                       Class<? extends Enum<?>> stateType,
                                       Map<String, ResolvedCCDConfig<?, ?, ?>> configs) {
    Set<String> matched = configs.entrySet().stream()
        .filter(entry -> entry.getValue().getCaseClass().equals(caseDataType)
            && entry.getValue().getStateClass().equals(stateType))
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());

    if (matched.isEmpty()) {
      throw new IllegalStateException(
          "Unable to match CaseView %s to any case type. "
              + "Ensure the generics match a registered CCD configuration."
              .formatted(view.getClass().getName()));
    }

    if (matched.size() > 1) {
      throw new IllegalStateException(
          "CaseView %s matches multiple case types (%s). "
              + "Provide distinct CaseView beans per case type."
              .formatted(view.getClass().getName(), String.join(", ", matched)));
    }

    return Set.copyOf(matched);
  }

  private record CaseViewBinding(
      CaseView<?, ?> caseView,
      Class<?> caseDataType,
      Class<? extends Enum<?>> stateType
  ) {}
}
