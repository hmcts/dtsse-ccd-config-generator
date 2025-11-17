package uk.gov.hmcts.ccd.sdk.servicebus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.SessionCallback;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CcdServiceBusConnectionValidatorTest {

    private final JmsTemplate jmsTemplate = mock(JmsTemplate.class);
    private final CcdServiceBusProperties properties = new CcdServiceBusProperties();
    private final ApplicationArguments args = mock(ApplicationArguments.class);

    @Test
    void shouldValidateDestinationOnStartup() throws Exception {
        properties.setDestination("ccd-case-events");
        when(jmsTemplate.execute(any(SessionCallback.class), eq(true))).thenReturn(null);

        new CcdServiceBusConnectionValidator(jmsTemplate, properties).run(args);

        verify(jmsTemplate).execute(any(SessionCallback.class), eq(true));
    }

    @Test
    void shouldSkipValidationWhenDestinationMissing() throws Exception {
        properties.setDestination(null);

        new CcdServiceBusConnectionValidator(jmsTemplate, properties).run(args);

        verify(jmsTemplate, never()).execute(any(SessionCallback.class), eq(true));
    }

    @Test
    void shouldFailStartupWhenValidationThrows() {
        properties.setDestination("ccd-case-events");
        when(jmsTemplate.execute(any(SessionCallback.class), eq(true)))
            .thenThrow(new JmsException("boom") { });

        assertThatThrownBy(() -> new CcdServiceBusConnectionValidator(jmsTemplate, properties).run(args))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to validate CCD Service Bus destination");
    }
}
