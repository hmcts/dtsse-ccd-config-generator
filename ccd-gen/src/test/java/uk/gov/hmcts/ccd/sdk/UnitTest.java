package uk.gov.hmcts.ccd.sdk;

import org.junit.Test;
import org.reflections.Reflections;

public class UnitTest {
    @Test
    public void multipleStatesPerEvent() {
        Reflections reflections = new Reflections("uk.gov.hmcts.reform");
        ConfigGenerator generator = new ConfigGenerator(reflections);
    }
}
