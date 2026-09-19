package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import feign.codec.Decoder;
import java.util.Arrays;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings("PMD.DataflowAnomalyAnalysis")
final class SnakeCaseFeignConfiguration {

  private SnakeCaseFeignConfiguration() {
    // utility class
  }

  static Decoder calendarFeignDecoder(JsonMapper objectMapper) {
    JacksonJsonHttpMessageConverter jacksonConverter =
        new JacksonJsonHttpMessageConverter(objectMapper);
    jacksonConverter.setSupportedMediaTypes(Arrays.asList(
        MediaType.valueOf(TEXT_PLAIN_VALUE + ";charset=utf-8"),
        APPLICATION_JSON,
        new MediaType("application", "*+json"),
        TEXT_PLAIN
    ));

    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("calendarJacksonConverter", (HttpMessageConverterCustomizer) converters -> {
      converters.clear();
      converters.add(jacksonConverter);
    });
    FeignHttpMessageConverters converters = new FeignHttpMessageConverters(
        beanFactory.getBeanProvider(ClientHttpMessageConvertersCustomizer.class),
        beanFactory.getBeanProvider(HttpMessageConverterCustomizer.class));
    beanFactory.addBean("calendarFeignHttpMessageConverters", converters);
    ObjectProvider<FeignHttpMessageConverters> converterProvider =
        beanFactory.getBeanProvider(FeignHttpMessageConverters.class);
    return new SpringDecoder(converterProvider);
  }
}
