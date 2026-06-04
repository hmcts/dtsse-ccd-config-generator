package uk.gov.hmcts.ccd.sdk.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ResourceLoader;
import uk.gov.hmcts.ccd.sdk.api.TypedCCDConfig;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackAdapterFactory;

public final class JsonCaseType<Case, State>
    extends JsonBackedCCDConfig<Case, State, JsonCaseTypeRole>
    implements TypedCCDConfig<Case, State, JsonCaseTypeRole> {

  private final Class<Case> caseDataClass;
  private final Class<State> stateClass;

  public JsonCaseType(Class<Case> caseDataClass,
                      Class<State> stateClass,
                      String caseTypeId,
                      String jsonRoot,
                      ResourceLoader resourceLoader,
                      ObjectMapper mapper,
                      JsonCallbackAdapterFactory callbackAdapterFactory) {
    super(caseTypeId, jsonRoot, resourceLoader, mapper, callbackAdapterFactory);
    this.caseDataClass = caseDataClass;
    this.stateClass = stateClass;
  }

  @Override
  public Class<Case> caseDataClass() {
    return caseDataClass;
  }

  @Override
  public Class<State> stateClass() {
    return stateClass;
  }

  @Override
  public Class<JsonCaseTypeRole> roleClass() {
    return JsonCaseTypeRole.class;
  }
}
