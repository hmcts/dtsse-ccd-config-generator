package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.jodah.typetools.TypeResolver;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;

/**
 * Central place for loading {@link DecentralisedCaseDetails} with the
 * application-provided projection applied. Today this delegates to
 * {@link BlobRepository} for persistence, but the orchestration lives here so
 * callers stay agnostic of how the raw data is sourced.
 */
@Service
class CaseViewLoader {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final BlobRepository blobRepository;
  private final ObjectMapper mapper;
  private final CaseView<Object> caseView;
  private final Class<?> caseDataType;

  CaseViewLoader(BlobRepository blobRepository,
                 ObjectMapper mapper,
                 CaseView<?> caseView) {
    this.blobRepository = blobRepository;
    this.mapper = mapper;
    @SuppressWarnings("unchecked")
    CaseView<Object> cast = (CaseView<Object>) caseView;
    this.caseView = cast;

    Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CaseView.class, caseView.getClass());
    this.caseDataType = typeArgs.length > 0 && typeArgs[0] != null ? typeArgs[0] : Map.class;
  }

  DecentralisedCaseDetails load(long caseRef) {
    DecentralisedCaseDetails raw = blobRepository.getCase(caseRef);
    return applyProjection(raw);
  }

  List<DecentralisedCaseDetails> load(List<Long> caseRefs) {
    return blobRepository.getCases(caseRefs).stream()
        .map(this::applyProjection)
        .collect(Collectors.toList());
  }

  private DecentralisedCaseDetails applyProjection(DecentralisedCaseDetails raw) {
    var caseDetails = raw.getCaseDetails();
    long reference = caseDetails.getReference();
    String state = caseDetails.getState();

    Object blobCase = deserialise(caseDetails.getData());
    Object projected = caseView.getCase(reference, state, blobCase);
    Map<String, JsonNode> serialised = serialise(projected);
    caseDetails.setData(serialised);
    return raw;
  }

  private Object deserialise(Map<String, JsonNode> data) {
    return mapper.convertValue(data, caseDataType);
  }

  private Map<String, JsonNode> serialise(Object projected) {
    return mapper.convertValue(projected, JSON_NODE_MAP);
  }
}
