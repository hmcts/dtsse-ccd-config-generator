package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.codec.Decoder;
import feign.codec.Encoder;
import java.util.Arrays;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

@SuppressWarnings("PMD.DataflowAnomalyAnalysis")
final class SnakeCaseFeignConfiguration {

  private SnakeCaseFeignConfiguration() {
    // utility class
  }

  static Decoder calendarFeignDecoder(ObjectMapper objectMapper) {
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

  static Encoder calendarFeignEncoder(ObjectMapper objectMapper) {
    HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter(objectMapper);
    ObjectFactory<HttpMessageConverters> objectFactory = () -> new HttpMessageConverters(jacksonConverter);
    return new SpringEncoder(objectFactory);
  }
}
