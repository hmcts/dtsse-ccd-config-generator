package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Banner;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

/**
 * Writes the jurisdiction service-notice banner set via {@link
 * uk.gov.hmcts.ccd.sdk.api.ConfigBuilder#banner}, read by {@code BannerParser}. Configs that
 * never call {@code banner(...)} emit no {@code Banner.json}.
 */
@Component
public class BannerGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    Banner banner = config.getBanner();
    if (banner == null) {
      return;
    }

    final Path path = Paths.get(outputFolder.getPath(), "Banner.json");
    final List<Map<String, Object>> rows = Lists.newArrayList(toJson(banner));
    // The importer allows one banner per jurisdiction, and this sheet has no key, so a banner already
    // written for another case type is the same row: it must agree with this one.
    mergeInto(path, rows, (key, existingValue, generatedValue) -> {
      if (!Objects.equals(existingValue, generatedValue)) {
        throw new IllegalStateException("Conflicting banner for case type " + config.getCaseType()
            + ": " + key + " is '" + generatedValue + "' but " + path + " already has '"
            + existingValue + "'. A jurisdiction has one banner.");
      }
      return existingValue;
    });
  }

  private static Map<String, Object> toJson(Banner banner) {
    Map<String, Object> row = Maps.newHashMap();
    row.put("BannerEnabled", JsonUtils.yn(banner.isEnabled()));
    row.put("BannerDescription", banner.getDescription());
    row.put("BannerUrl", banner.getUrl());
    row.put("BannerUrlText", banner.getUrlText());
    return row;
  }
}
