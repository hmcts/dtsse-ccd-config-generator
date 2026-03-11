package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.codec.Decoder;
import feign.codec.Encoder;
import java.util.Arrays;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

@Configuration
@SuppressWarnings("PMD.DataflowAnomalyAnalysis")
class SnakeCaseFeignConfiguration {

  private final ObjectMapper objectMapper;

  @Autowired
  SnakeCaseFeignConfiguration(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Bean
  Decoder calendarFeignDecoder() {
    MappingJackson2HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter(objectMapper);
    jacksonConverter.setSupportedMediaTypes(Arrays.asList(
        MediaType.valueOf(TEXT_PLAIN_VALUE + ";charset=utf-8"),
        APPLICATION_JSON,
        new MediaType("application", "*+json"),
        TEXT_PLAIN
    ));
    ObjectFactory<HttpMessageConverters> objectFactory = () -> new HttpMessageConverters(jacksonConverter);
    return new ResponseEntityDecoder(new SpringDecoder(objectFactory));
  }

  @Bean
  Encoder calendarFeignEncoder() {
    HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter(objectMapper);
    ObjectFactory<HttpMessageConverters> objectFactory = () -> new HttpMessageConverters(jacksonConverter);
    return new SpringEncoder(objectFactory);
  }
}
