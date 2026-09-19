package uk.gov.hmcts.ccd.sdk;

import java.util.ArrayList;
import java.util.List;
import org.gradle.api.Action;

/** Configuration for the compiled-class Jackson 2 compatibility guard. */
public class JacksonClasspathGuardExtension {
  private final JacksonClasspathGuardOptions jackson2ClasspathGuard = new JacksonClasspathGuardOptions();

  public JacksonClasspathGuardOptions getJackson2ClasspathGuard() {
    return jackson2ClasspathGuard;
  }

  public void jackson2ClasspathGuard(Action<? super JacksonClasspathGuardOptions> action) {
    action.execute(jackson2ClasspathGuard);
  }

  public static class JacksonClasspathGuardOptions {
    private List<String> firstPartyGroups = new ArrayList<>(List.of("com.github.hmcts", "uk.gov.hmcts"));

    public List<String> getFirstPartyGroups() {
      return firstPartyGroups;
    }

    public void setFirstPartyGroups(List<String> firstPartyGroups) {
      this.firstPartyGroups = new ArrayList<>(firstPartyGroups);
    }

  }

}
