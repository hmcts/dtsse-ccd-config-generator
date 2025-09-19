package uk.gov.hmcts.ccd.sdk.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class LocalDateTimeSerializerTest {

    @Mock
    private JsonGenerator jsonGenerator;

    @Mock
    private SerializerProvider serializerProvider;

    @Test
    public void shouldAssertSerializationMethodCalled() throws IOException {

        LocalDateTime date = LocalDateTime.of(2022, 4, 21, 10, 22, 33 );
        String dateTimeWithNoMilliSeconds = "2022-04-21T10:22:33";

        LocalDateTimeSerializer serializer = new LocalDateTimeSerializer();
        serializer.serialize(date, jsonGenerator, serializerProvider);

        verify(jsonGenerator).writeString(dateTimeWithNoMilliSeconds);
    }
}
