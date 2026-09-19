package uk.gov.hmcts.ccd.sdk.deserializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

public class LocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {

  private static final long serialVersionUID = 1L;

  protected LocalDateTimeDeserializer() {
    super(LocalDateTime.class);
  }

  @Override
  public LocalDateTime deserialize(JsonParser jp, DeserializationContext ctxt) throws JacksonException {
    var dateString = jp.readValueAs(String.class);
    DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    return LocalDateTime.parse(dateString, formatter);
  }
}
