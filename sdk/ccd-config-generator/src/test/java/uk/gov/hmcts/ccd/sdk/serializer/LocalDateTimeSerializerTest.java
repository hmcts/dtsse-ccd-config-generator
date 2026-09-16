package uk.gov.hmcts.ccd.sdk.serializer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDateTime;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

public class LocalDateTimeSerializerTest {

    @Test
    public void shouldSerializeDateTimeWithoutMilliSeconds() throws Exception {

        LocalDateTime date = LocalDateTime.of(2022, 4, 21, 10, 22, 33 );
        var module = new SimpleModule()
            .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer());
        var mapper = JsonMapper.builder()
            .addModule(module)
            .build();

        assertThat(mapper.writeValueAsString(date), equalTo("\"2022-04-21T10:22:33\""));
    }
}
