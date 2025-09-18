package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.TabField;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class CaseTypeTabGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  // The field type set from code always takes precedence,
  // so eg. if a field changes type it gets updated.
  private static final ImmutableSet<String> OVERWRITES_FIELDS = ImmutableSet.of();

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {

    List<Map<String, Object>> result = Lists.newArrayList();

    int tabDisplayOrder = 1;
    // For backwards compatibility we automatically define a history tab as the first tab if the app doesn't set one.
    if (!config.getTabs().stream().anyMatch(x -> x.getTabID().equals("CaseHistory"))) {
      result.add(buildField(config.getCaseType(), "CaseHistory", "caseHistory", "History", 1, 1, ""));
      tabDisplayOrder = 2;
    }

    List<Map<String, Object>> caseFields = Lists.newArrayList();


    for (Tab<T, R> tab : config.getTabs()) {
      List<String> roles = tab.getForRolesAsString();

      // if no roles have been specified leave UserRole empty
      if (roles.isEmpty()) {
        roles.add("");
      }

      for (String role : roles) {
        addTab(config.getCaseType(), result, caseFields, tabDisplayOrder++, tab, role);
      }
    }

    Path tabDir = Paths.get(root.getPath(), "CaseTypeTab");
    tabDir.toFile().mkdirs();
    for (Map<String, Object> tab : result) {
      Path output = tabDir.resolve(tab.get("TabDisplayOrder") + "_" + tab.get("TabID") + ".json");
      JsonUtils.mergeInto(output, Lists.newArrayList(tab), new AddMissing(),
          "TabID", "CaseFieldID");
    }
    // Add any dynamically declared tab labels into our case fields
    Path path = Paths.get(root.getPath(), "CaseField.json");
    JsonUtils.mergeInto(path, caseFields, new JsonUtils.OverwriteSpecific(OVERWRITES_FIELDS), "ID");
  }

  private static <T, R extends HasRole> void addTab(String caseType, List<Map<String, Object>> result,
                                                    List<Map<String, Object>> caseFields,
                                                    int tabDisplayOrder, Tab<T, R> tab, String role) {
    int tabFieldDisplayOrder = 1;
    for (TabField tabField : tab.getFields()) {
      Map<String, Object> field = buildField(caseType, tab.getTabID() + role, tabField.getId(),
          tab.getLabelText(), tabDisplayOrder, tabFieldDisplayOrder, role);
      if (tab.getShowCondition() != null && tabFieldDisplayOrder == 1) {
        field.put("TabShowCondition", tab.getShowCondition());
      }
      if (tabField.getShowCondition() != null) {
        field.put("FieldShowCondition", tabField.getShowCondition());
      }
      if (tabField.getDisplayContextParameter() != null) {
        field.put("DisplayContextParameter", tabField.getDisplayContextParameter());
      }
      if (tabField.getLabel() != null) {
        Map<String, Object> fieldInfo = buildLabelField(caseType, tabField.getId());
        fieldInfo.put("FieldType", "Label");
        fieldInfo.put("Label", tabField.getLabel());
        caseFields.add(fieldInfo);
      }
      result.add(field);
      tabFieldDisplayOrder++;
    }
  }

  private static Map<String, Object> buildField(String caseType, String tabId, String fieldId,
      String tabLabel, int displayOrder, int tabFieldDisplayOrder, String role) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("Channel", "CaseWorker");
    field.put("TabID", tabId);
    field.put("TabLabel", tabLabel);
    field.put("UserRole", tabFieldDisplayOrder == 1 ? role : "");
    field.put("TabDisplayOrder", displayOrder);
    field.put("TabFieldDisplayOrder", tabFieldDisplayOrder);
    field.put("CaseFieldID", fieldId);
    return field;
  }

  public static Map<String, Object> buildLabelField(String caseType, String id) {
    Map<String, Object> result = new Hashtable<>();
    result.put("LiveFrom", "01/01/2017");
    result.put("CaseTypeID", caseType);
    result.put("ID", id);
    result.put("SecurityClassification", "Public");
    return result;
  }
}

