package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.type.AddressGlobalUK;

/** Configures the Jackson 2 mapper used exclusively for consumer case-data classes. */
public final class Jackson2CaseDataMapper {

  private Jackson2CaseDataMapper() {
  }

  public static ObjectMapper configured(Optional<ObjectMapper> applicationMapper) {
    ObjectMapper mapper = applicationMapper.orElseGet(ObjectMapper::new).copy();
    mapper.enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS);
    mapper.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    mapper.disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    mapper.disable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
    mapper.disable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
    SimpleModule compatibility = new SimpleModule("ccd-jackson2-case-data");
    compatibility.addDeserializer(AddressGlobalUK.class, new AddressGlobalUkDeserializer());
    mapper.registerModule(compatibility);
    return mapper;
  }

  private static final class AddressGlobalUkDeserializer
      extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<AddressGlobalUK> {

    private AddressGlobalUkDeserializer() {
      super(AddressGlobalUK.class);
    }

    @Override
    public AddressGlobalUK deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                       com.fasterxml.jackson.databind.DeserializationContext context)
        throws IOException {
      com.fasterxml.jackson.databind.JsonNode address = parser.getCodec().readTree(parser);
      return new AddressGlobalUK(
          text(address, "AddressLine1"),
          text(address, "AddressLine2"),
          text(address, "AddressLine3"),
          text(address, "PostTown"),
          text(address, "County"),
          text(address, "PostCode"),
          text(address, "Country")
      );
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode address, String field) {
      com.fasterxml.jackson.databind.JsonNode value = address.get(field);
      return value == null || value.isNull() ? null : value.asText();
    }
  }
}
