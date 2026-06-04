package uk.gov.hmcts.ccd.sdk.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackBridge;

@Component
@RequiredArgsConstructor
public class JsonBackedCCDConfigFactory {

  private final ResourceLoader resourceLoader;
  private final ObjectMapper mapper;
  private final JsonCallbackBridge callbackBridge;

  ResourceLoader resourceLoader() {
    return resourceLoader;
  }

  ObjectMapper mapper() {
    return mapper;
  }

  JsonCallbackBridge callbackBridge() {
    return callbackBridge;
  }
}
