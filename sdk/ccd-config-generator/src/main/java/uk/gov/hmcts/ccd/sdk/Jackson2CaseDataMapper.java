package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.type.AddressGlobalUK;
import uk.gov.hmcts.ccd.sdk.type.DynamicList;
import uk.gov.hmcts.ccd.sdk.type.DynamicListElement;
import uk.gov.hmcts.ccd.sdk.type.Fee;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.OrderSummary;

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
    compatibility.addDeserializer(DynamicList.class, new DynamicListDeserializer());
    compatibility.addDeserializer(OrderSummary.class, new OrderSummaryDeserializer());
    mapper.registerModule(compatibility);
    return mapper;
  }

  private static final class DynamicListDeserializer
      extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<DynamicList> {

    private DynamicListDeserializer() {
      super(DynamicList.class);
    }

    @Override
    public DynamicList deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                   com.fasterxml.jackson.databind.DeserializationContext context)
        throws IOException {
      com.fasterxml.jackson.databind.JsonNode dynamicList = parser.getCodec().readTree(parser);
      DynamicListElement value = dynamicListElement(dynamicList.get("value"));
      List<DynamicListElement> items = null;
      com.fasterxml.jackson.databind.JsonNode itemData = dynamicList.get("list_items");
      if (itemData != null && itemData.isArray()) {
        items = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode item : itemData) {
          items.add(dynamicListElement(item));
        }
      }
      return new DynamicList(value, items);
    }

    private static DynamicListElement dynamicListElement(
        com.fasterxml.jackson.databind.JsonNode element) {
      if (element == null || element.isNull()) {
        return null;
      }
      String code = text(element, "code");
      return new DynamicListElement(code == null ? null : UUID.fromString(code), text(element, "label"));
    }
  }

  private static final class OrderSummaryDeserializer
      extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<OrderSummary> {

    private OrderSummaryDeserializer() {
      super(OrderSummary.class);
    }

    @Override
    public OrderSummary deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                    com.fasterxml.jackson.databind.DeserializationContext context)
        throws IOException {
      com.fasterxml.jackson.databind.JsonNode summary = parser.getCodec().readTree(parser);
      List<ListValue<Fee>> fees = null;
      com.fasterxml.jackson.databind.JsonNode feeItems = summary.get("Fees");
      if (feeItems != null && feeItems.isArray()) {
        fees = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode feeItem : feeItems) {
          com.fasterxml.jackson.databind.JsonNode feeData = feeItem.get("value");
          Fee fee = new Fee(
              text(feeData, "FeeAmount"),
              text(feeData, "FeeCode"),
              text(feeData, "FeeDescription"),
              text(feeData, "FeeVersion")
          );
          fees.add(new ListValue<>(text(feeItem, "id"), fee));
        }
      }
      return new OrderSummary(
          text(summary, "PaymentReference"),
          fees,
          text(summary, "PaymentTotal")
      );
    }
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode object, String field) {
    if (object == null) {
      return null;
    }
    com.fasterxml.jackson.databind.JsonNode value = object.get(field);
    return value == null || value.isNull() ? null : value.asText();
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
          Jackson2CaseDataMapper.text(address, "AddressLine1"),
          Jackson2CaseDataMapper.text(address, "AddressLine2"),
          Jackson2CaseDataMapper.text(address, "AddressLine3"),
          Jackson2CaseDataMapper.text(address, "PostTown"),
          Jackson2CaseDataMapper.text(address, "County"),
          Jackson2CaseDataMapper.text(address, "PostCode"),
          Jackson2CaseDataMapper.text(address, "Country")
      );
    }
  }
}
