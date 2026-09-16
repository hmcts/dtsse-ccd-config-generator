package uk.gov.hmcts.ccd.sdk.serializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public class LocalDateTimeSerializer extends ValueSerializer<LocalDateTime> {

  @Override
  public void serialize(LocalDateTime date, JsonGenerator jsonGenerator,
                        SerializationContext context) throws JacksonException {
    DateTimeFormatter dateFormat = DateTimeFormatter.ISO_DATE_TIME;
    String dateString = dateFormat.format(date);
    jsonGenerator.writeString(dateString);
  }
}
