package uk.gov.hmcts.ccd.sdk.servicebus;

import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.SessionCallback;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(name = "spring.jms.servicebus.enabled", havingValue = "true")
@RequiredArgsConstructor
class CcdServiceBusConnectionValidator implements ApplicationRunner {

  private final JmsTemplate jmsTemplate;
  private final CcdServiceBusProperties properties;

  @Override
  public void run(ApplicationArguments args) {
    String destination = properties.getDestination();
    if (destination == null || destination.isBlank()) {
      log.debug("Skipping CCD Service Bus validation because no destination is configured");
      return;
    }

    try {
      jmsTemplate.execute(new ProducerProbe(destination), true);
      log.info("Validated CCD Service Bus destination {}", destination);
    } catch (JmsException ex) {
      throw new IllegalStateException("Failed to validate CCD Service Bus destination " + destination, ex);
    }
  }

  private class ProducerProbe implements SessionCallback<Void> {

    private final String destinationName;

    ProducerProbe(String destinationName) {
      this.destinationName = destinationName;
    }

    @Override
    public Void doInJms(Session session) throws JMSException {
      Destination destination = jmsTemplate.getDestinationResolver()
          .resolveDestinationName(session, destinationName, jmsTemplate.isPubSubDomain());
      MessageProducer producer = session.createProducer(destination);
      producer.close();
      return null;
    }
  }
}
