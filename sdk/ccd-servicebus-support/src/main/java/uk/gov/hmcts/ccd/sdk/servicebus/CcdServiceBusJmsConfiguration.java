package uk.gov.hmcts.ccd.sdk.servicebus;

import jakarta.jms.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(CcdServiceBusProperties.class)
@ConditionalOnProperty(name = "spring.jms.servicebus.enabled", havingValue = "true")
public class CcdServiceBusJmsConfiguration {

  @Bean
  @Primary
  public JmsTemplate jmsTemplate(ConnectionFactory jmsConnectionFactory, MessageConverter messageConverter) {
    // Let Spring Cloud Azure configure the recommended JmsPoolConnectionFactory; avoid wrapping with caching.
    JmsTemplate jmsTemplate = new JmsTemplate(jmsConnectionFactory);
    jmsTemplate.setMessageConverter(messageConverter);
    return jmsTemplate;
  }

  @Bean
  public MessageConverter ccdServiceBusMessageConverter(JsonMapper objectMapper) {
    JsonMapper mapper = objectMapper.rebuild()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
    CcdMessageConverter converter = new CcdMessageConverter(mapper);
    converter.setTargetType(MessageType.BYTES);
    converter.setTypeIdPropertyName("_type");
    return converter;
  }

}
