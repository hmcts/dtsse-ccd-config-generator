package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

class ComplexTypeGenerator {

  public static void generate(File root, String caseType, Map<Class<?>, Integer> types) {
    File complexTypes = new File(root, "ComplexTypes");
    complexTypes.mkdir();
    types = types.entrySet().stream().filter(x -> !x.getKey().isEnum())
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

      List<Map<String, Object>> fields = CaseFieldGenerator.toComplex(c, caseType);

      for (Map<String, Object> info : fields) {
        info.put("ListElementCode", info.get("ID"));
        if (info.containsKey("Label")) {
          info.put("ElementLabel", info.remove("Label"));
        }
        info.put("ID", id);
        info.remove("CaseTypeID");
      }

      int depth = types.get(c);
      Path path;
      if (0 == depth) {
        path = Paths.get(complexTypes.getPath(), id + ".json");
      } else {
        String prefix = maxDepth - depth + "_";
        path = Paths.get(complexTypes.getPath(), prefix + id + ".json");
      }
      JsonUtils.mergeInto(path, fields, new AddMissing(), "ListElementCode");
    }
  }

}
