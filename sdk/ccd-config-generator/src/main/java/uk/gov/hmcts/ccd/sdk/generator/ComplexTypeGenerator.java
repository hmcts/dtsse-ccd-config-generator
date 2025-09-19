package uk.gov.hmcts.ccd.sdk.generator;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class ComplexTypeGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    File complexTypes = new File(root, "ComplexTypes");
    complexTypes.mkdir();
    Map<Class, Integer> types = config.getTypes().entrySet().stream().filter(x -> !x.getKey().isEnum())
        .collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue()));

    if (types.isEmpty()) {
      return;
    }

    int maxDepth = types.values().stream().mapToInt(Integer::intValue).max().getAsInt();

    for (Class<?> c : types.keySet()) {
      ComplexType complex = c.getAnnotation(ComplexType.class);
      String id =
          null != complex && complex.name().length() > 0 ? complex.name() : c.getSimpleName();
      if (null != complex && !complex.generate()) {
        continue;
      }

      List<Map<String, Object>> fields = CaseFieldGenerator.toComplex(c, config.getCaseType());

      for (Map<String, Object> info : fields) {
        info.put("ListElementCode", info.get("ID"));
        if (info.containsKey("Label")) {
          info.put("ElementLabel", info.remove("Label"));
        }
        info.put("ID", id);
        info.remove("CaseTypeID");
      }
      // CCD requires Complex types to be declared in order.
      // ie. if type A references type B, B must be declared first or import will fail.
      // We therefore sort our complex types by depth from the root to ensure this ordering.
      int depth = types.get(c);
      Path path;
      if (0 == depth) {
        path = Paths.get(complexTypes.getPath(), id + ".json");
      } else {
        String prefix = maxDepth - depth + "_";
        path = Paths.get(complexTypes.getPath(), prefix + id + ".json");
      }

      sortComplexTypesByDisplayOrder(fields);
      JsonUtils.mergeInto(path, fields, new AddMissing(), false, "ListElementCode");
    }
  }

  public void sortComplexTypesByDisplayOrder(List<Map<String, Object>> fields) {

    Collections.sort(fields, new Comparator<Map<String,Object>>() {
      @Override
      public int compare(Map<String,Object> o1, Map<String,Object> o2) {

        Integer listOrder1 = (Integer)o1.get("DisplayOrder");
        Integer listOrder2 = (Integer)o2.get("DisplayOrder");

        if (listOrder1 == null) {
          return listOrder2 == null ? 0 : 1;
        } else if (listOrder2 == null) {
          return -1;
        }
        return listOrder1 - listOrder2;
      }
    });

  }

}
