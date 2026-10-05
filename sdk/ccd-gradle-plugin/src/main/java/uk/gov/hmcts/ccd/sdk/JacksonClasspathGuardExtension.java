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
    private List<String> allowedSourceFiles = new ArrayList<>();
    private List<String> allowedClasses = new ArrayList<>();
    private List<String> allowedComponents = new ArrayList<>();

    public List<String> getFirstPartyGroups() {
      return firstPartyGroups;
    }

    public void setFirstPartyGroups(List<String> firstPartyGroups) {
      this.firstPartyGroups = new ArrayList<>(firstPartyGroups);
    }

    public List<String> getAllowedSourceFiles() {
      return allowedSourceFiles;
    }

    public void setAllowedSourceFiles(List<String> allowedSourceFiles) {
      this.allowedSourceFiles = new ArrayList<>(allowedSourceFiles);
    }

    public List<String> getAllowedClasses() {
      return allowedClasses;
    }

    public void setAllowedClasses(List<String> allowedClasses) {
      this.allowedClasses = new ArrayList<>(allowedClasses);
    }

    public List<String> getAllowedComponents() {
      return allowedComponents;
    }

    public void setAllowedComponents(List<String> allowedComponents) {
      this.allowedComponents = new ArrayList<>(allowedComponents);
    }

  }

}
