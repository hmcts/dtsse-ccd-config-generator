package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.external.ClientContext;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalRejectionException;

/** A context a unit test gives a start handler is read as the runtime reads a frontend's. */
public class ClientContextTest {

    record Visit(String name, String orderId) {
    }

    record Addressee(String name) {
    }

    @Test
    public void aValueIsReadAsTheTypeTheHandlerAsksFor() {
        ClientContext context = ClientContext.of(new Visit("Sam", "42"));

        assertThat(context.as(Addressee.class)).contains(new Addressee("Sam"));
        assertThat(ClientContext.of(42).as(int.class)).contains(42);
        assertThat(ClientContext.none().as(Addressee.class)).isEmpty();
    }

    @Test
    public void aValueThatCannotBeReadAsTheTypeRejectsTheStart() {
        assertThatThrownBy(() -> ClientContext.of("Sam").as(Addressee.class))
            .isInstanceOf(ExternalRejectionException.class)
            .hasMessage("The client context is not a valid Addressee");
    }
}
