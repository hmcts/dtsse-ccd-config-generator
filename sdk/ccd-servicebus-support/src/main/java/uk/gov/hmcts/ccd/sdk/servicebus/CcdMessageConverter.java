package uk.gov.hmcts.ccd.sdk.servicebus;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import java.io.IOException;
import org.apache.qpid.jms.message.JmsBytesMessage;
import org.apache.qpid.jms.provider.amqp.message.AmqpJmsMessageFacade;
import org.apache.qpid.proton.amqp.Symbol;
import org.springframework.jms.support.converter.JacksonJsonMessageConverter;
import tools.jackson.databind.ObjectWriter;

/**
 * JMS MessageConverter that supports publishing JSON content types in Azure Service Bus.
 */
public class CcdMessageConverter extends JacksonJsonMessageConverter {

  public CcdMessageConverter(tools.jackson.databind.json.JsonMapper mapper) {
    super(mapper);
  }

  @Override
  protected BytesMessage mapToBytesMessage(Object object, Session session, ObjectWriter objectWriter)
      throws JMSException, IOException {
    BytesMessage message = super.mapToBytesMessage(object, session, objectWriter);
    if (message instanceof JmsBytesMessage qpidMessage) {
      AmqpJmsMessageFacade facade = (AmqpJmsMessageFacade) qpidMessage.getFacade();
      facade.setContentType(Symbol.valueOf(APPLICATION_JSON_VALUE));
    }
    return message;
  }
}
