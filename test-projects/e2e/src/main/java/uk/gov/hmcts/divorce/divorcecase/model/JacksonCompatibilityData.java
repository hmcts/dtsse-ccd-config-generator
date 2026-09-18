package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * Legacy model patterns whose stored JSON must survive a mapper upgrade.
 */
@Data
@ComplexType(generate = false)
public class JacksonCompatibilityData {

    private GetterOnlyCollections evidence;
    private LowercaseAccessors legacyReference;
    private MonetaryAmount monetaryAmount;

    // Jackson 3's built-in java.time.Month serializer writes a one-based number unconditionally and ignores
    // @JsonFormat(shape = STRING) entirely (it only reacts to an explicit pattern, which still wouldn't
    // reproduce the exact enum name). A small serializer/deserializer pair is the only way to keep this as a
    // plain name; the SDK's case-data mapper cannot do this for you.
    @JsonSerialize(using = MonthNameSerializer.class)
    @JsonDeserialize(using = MonthNameDeserializer.class)
    private Month hearingMonth;

    private static final class MonthNameSerializer extends ValueSerializer<Month> {
        @Override
        public void serialize(Month value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.name());
        }
    }

    private static final class MonthNameDeserializer extends ValueDeserializer<Month> {
        @Override
        public Month deserialize(JsonParser parser, DeserializationContext context) {
            return Month.valueOf(parser.getString());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @ComplexType(generate = false)
    public static class GetterOnlyCollections {

        private final List<String> documents = new ArrayList<>();

        private final Map<String, String> answers = new LinkedHashMap<>();

        // No matching field or setter: Jackson 2 populated the returned collections.
        public List<String> getDocumentIds() {
            return documents;
        }

        public Map<String, String> getRecordedAnswers() {
            return answers;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @ComplexType(generate = false)
    public static class LowercaseAccessors {

        private String value;

        // Jackson 2 accepted lowercase characters immediately after get/set; Jackson 3 tightened accessor-name
        // detection and does not recognise these as a property at all, under any mapper setting. @JsonProperty
        // is the required per-accessor migration step.
        @JsonProperty("reference")
        public String getreference() {
            return value;
        }

        @JsonProperty("reference")
        public void setreference(String value) {
            this.value = value;
        }
    }

    @Data
    @ComplexType(generate = false)
    public static class MonetaryAmount {

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal amount;

        public MonetaryAmount() {
        }

        // Construction of new amounts rounds; reloading stored amounts used the no-arg constructor and setter.
        public MonetaryAmount(BigDecimal amount) {
            this.amount = amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
