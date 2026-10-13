package uk.gov.hmcts.divorce.roundtrip;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class RoundTripFixtureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesExplicitNull() {
        ObjectNode expected = mapper.createObjectNode().putNull("value");
        ObjectNode actual = mapper.createObjectNode().putNull("value");

        assertThat(RoundTripFixture.lostOrChanged(expected, actual)).isEmpty();
    }

    @Test
    void distinguishesExplicitNullFromMissingValue() {
        ObjectNode expected = mapper.createObjectNode().putNull("value");

        assertThat(RoundTripFixture.lostOrChanged(expected, mapper.createObjectNode()))
            .containsExactly("LOST    .value expected null");
    }

    @Test
    void reportsNonNullValueChangedToNullAsLost() {
        ObjectNode expected = mapper.createObjectNode().put("value", "present");
        ObjectNode actual = mapper.createObjectNode().putNull("value");

        assertThat(RoundTripFixture.lostOrChanged(expected, actual))
            .containsExactly("LOST    .value expected \"present\"");
    }
}
