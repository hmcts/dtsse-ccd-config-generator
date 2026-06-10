package uk.gov.hmcts.ccd.sdk.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
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
