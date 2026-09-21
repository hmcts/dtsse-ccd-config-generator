package uk.gov.hmcts.ccd.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gov.hmcts.ccd.sdk.Jackson2CaseDataMapper;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

/** Keeps the SDK callback wire contract independent of consumer-wide Jackson customisation. */
@Configuration(proxyBeanMethods = false)
class CallbackJackson2Configuration {

  @Bean
  WebMvcConfigurer ccdCallbackJackson2Converter(Optional<ObjectMapper> applicationMapper) {
    ObjectMapper mapper = Jackson2CaseDataMapper.configured(applicationMapper);
    return new WebMvcConfigurer() {
      @Override
      @SuppressWarnings("removal")
      public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new CallbackResponseConverter(mapper));
      }
    };
  }

  @SuppressWarnings("removal")
  private static final class CallbackResponseConverter extends MappingJackson2HttpMessageConverter {

    private CallbackResponseConverter(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    protected boolean supports(Class<?> type) {
      return AboutToStartOrSubmitResponse.class.isAssignableFrom(type)
          || SubmittedCallbackResponse.class.isAssignableFrom(type);
    }

    @Override
    public boolean canRead(Class<?> type, MediaType mediaType) {
      return false;
    }

    @Override
    public boolean canRead(java.lang.reflect.Type type, Class<?> contextClass, MediaType mediaType) {
      return false;
    }

    @Override
    public boolean canWrite(Class<?> type, MediaType mediaType) {
      return supports(type) && super.canWrite(type, mediaType);
    }
  }
}
