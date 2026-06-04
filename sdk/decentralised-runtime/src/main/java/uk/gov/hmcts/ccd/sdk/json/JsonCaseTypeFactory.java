package uk.gov.hmcts.ccd.sdk.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackAdapterFactory;

@Component
@RequiredArgsConstructor
public class JsonCaseTypeFactory {

  private final ResourceLoader resourceLoader;
  private final ObjectMapper mapper;
  private final JsonCallbackAdapterFactory callbackAdapterFactory;

  public <Case, State> JsonCaseType<Case, State> build(Class<Case> caseDataClass,
                                                       Class<State> stateClass,
                                                       String caseTypeId,
                                                       String jsonRoot) {
    return new JsonCaseType<>(
        caseDataClass,
        stateClass,
        caseTypeId,
        jsonRoot,
        resourceLoader,
        mapper,
        callbackAdapterFactory
    );
  }
}
