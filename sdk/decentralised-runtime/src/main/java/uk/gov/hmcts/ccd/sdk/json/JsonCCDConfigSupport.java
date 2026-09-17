package uk.gov.hmcts.ccd.sdk.json;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackBridge;

@Component
@Getter(AccessLevel.PACKAGE)
@RequiredArgsConstructor
@Accessors(fluent = true)
public class JsonCCDConfigSupport {

  private final ResourceLoader resourceLoader;
  private final ObjectMapper mapper;
  private final JsonCallbackBridge callbackBridge;
}
