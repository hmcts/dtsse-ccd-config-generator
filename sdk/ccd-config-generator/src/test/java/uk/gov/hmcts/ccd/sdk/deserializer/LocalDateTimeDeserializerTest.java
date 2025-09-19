package uk.gov.hmcts.ccd.sdk.deserializer;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class LocalDateTimeDeserializerTest {

  private ObjectMapper mapper;
  private LocalDateTimeDeserializer deserializer;

  @Before
  public void setup() {
    mapper = new ObjectMapper();
    deserializer = new LocalDateTimeDeserializer();
  }

  @Test
  public void shouldDeserializeDateTimeWithNoMilliSeconds() {
    String dateTimeWithNoMilliSeconds = "{\"value\":\"2022-04-21T10:22:33\"}";

    LocalDateTime deserializedValue = deserializeDateTime(dateTimeWithNoMilliSeconds);

    assertDateValue(deserializedValue);
  }

  @Test
  public void shouldDeserializeDateTimeWithTwoMilliSeconds() {
    String dateTimeWithTwoMilliSeconds = "{\"value\":\"2022-04-21T10:22:33.44\"}";

    LocalDateTime deserializedValue = deserializeDateTime(dateTimeWithTwoMilliSeconds);

    assertDateValue(deserializedValue);
  }

  @Test
  public void shouldDeserializeDateTimeWithThreeMilliSeconds() {
    String dateTimeWithThreeMilliSeconds = "{\"value\":\"2022-04-21T10:22:33.555\"}";

    LocalDateTime deserializedValue = deserializeDateTime(dateTimeWithThreeMilliSeconds);

    assertDateValue(deserializedValue);
  }

  @Test
  public void shouldDeserializeDateTimeWithOneMilliSeconds() {
    String dateTimeWithOneMilliSecond = "{\"value\":\"2022-04-21T10:22:33.6\"}";

    LocalDateTime deserializedValue = deserializeDateTime(dateTimeWithOneMilliSecond);

    assertThat(deserializedValue, instanceOf(LocalDateTime.class));
  }

  @SneakyThrows({JsonParseException.class, IOException.class})
  private LocalDateTime deserializeDateTime(String json) {
    InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    JsonParser parser = mapper.getFactory().createParser(stream);
    DeserializationContext ctxt = mapper.getDeserializationContext();
    parser.nextToken();
    parser.nextToken();
    parser.nextToken();
    return deserializer.deserialize(parser, ctxt);
  }

  private void assertDateValue(LocalDateTime dateTime) {
    assertThat(dateTime, instanceOf(LocalDateTime.class));

    assertThat(dateTime.getYear(), equalTo(2022));
    assertThat(dateTime.getMonthValue(), equalTo(4));
    assertThat(dateTime.getDayOfMonth(), equalTo(21));
    assertThat(dateTime.getHour(), equalTo(10));
    assertThat(dateTime.getMinute(), equalTo(22));
    assertThat(dateTime.getSecond(), equalTo(33));
  }
}
