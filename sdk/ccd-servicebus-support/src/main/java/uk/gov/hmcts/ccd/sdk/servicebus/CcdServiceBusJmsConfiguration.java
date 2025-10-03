package uk.gov.hmcts.ccd.sdk.servicebus;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

@Configuration
@EnableConfigurationProperties(CcdServiceBusProperties.class)
@ConditionalOnProperty(name = "spring.jms.servicebus.enabled", havingValue = "true")
public class CcdServiceBusJmsConfiguration {

  @Bean
  @Primary
  public JmsTemplate jmsTemplate(ConnectionFactory jmsConnectionFactory, MessageConverter messageConverter) {
    JmsTemplate jmsTemplate = new JmsTemplate();
    jmsTemplate.setMessageConverter(messageConverter);
    CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory(jmsConnectionFactory);
    cachingConnectionFactory.setCacheProducers(false);
    jmsTemplate.setConnectionFactory(cachingConnectionFactory);
    return jmsTemplate;
  }

  @Bean
  public MessageConverter ccdServiceBusMessageConverter(ObjectMapper objectMapper) {
    MappingJackson2MessageConverter converter = new CcdMessageConverter();
    ObjectMapper mapper = objectMapper.copy();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    converter.setObjectMapper(mapper);
    converter.setTargetType(MessageType.BYTES);
    converter.setTypeIdPropertyName("_type");
    return converter;
  }

}
