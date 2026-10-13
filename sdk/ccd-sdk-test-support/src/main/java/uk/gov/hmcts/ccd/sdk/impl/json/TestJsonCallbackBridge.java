package uk.gov.hmcts.ccd.sdk.impl.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;

/** Keeps unrelated JSON callbacks out of a focused event test's startup validation. */
public class TestJsonCallbackBridge extends JsonCallbackBridge {

  public TestJsonCallbackBridge(ApplicationContext applicationContext,
                                @Qualifier(CcdCaseDataMapperConfiguration.CCD_CASE_DATA_OBJECT_MAPPER)
                                ObjectMapper mapper,
                                @Qualifier("requestMappingHandlerMapping")
                                RequestMappingHandlerMapping handlerMapping,
                                Environment environment) {
    super(applicationContext, mapper, handlerMapping, environment);
  }

  @Override
  public void validate(String callbackUrl) {
    // The focused context may not contain the controllers for other events in the JSON definition.
  }
}
