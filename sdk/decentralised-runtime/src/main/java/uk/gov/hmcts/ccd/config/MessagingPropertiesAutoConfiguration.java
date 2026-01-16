package uk.gov.hmcts.ccd.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class MessagingPropertiesAutoConfiguration {

  @Bean
  @ConfigurationProperties(prefix = "ccd.messaging")
  public MessagingProperties messagingProperties() {
    Map<String, String> typeMappings = new LinkedHashMap<>();
    typeMappings.put("Text", "SimpleText");
    typeMappings.put("PhoneUK", "SimpleText");
    typeMappings.put("Email", "SimpleText");
    typeMappings.put("TextArea", "SimpleText");
    typeMappings.put("BaseLocation", "SimpleText");
    typeMappings.put("Region", "SimpleText");
    typeMappings.put("Date", "SimpleDate");
    typeMappings.put("DateTime", "SimpleDateTime");
    typeMappings.put("Number", "SimpleNumber");
    typeMappings.put("MoneyGBP", "SimpleNumber");
    typeMappings.put("YesOrNo", "SimpleBoolean");
    typeMappings.put("Document", "Complex");
    MessagingProperties properties = new MessagingProperties();
    properties.setTypeMappings(typeMappings);
    return properties;
  }
}
