package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Converts generated CCD JSON definitions to an XLSX workbook using an existing CCD template.
 */
public final class XlsxMain {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {
  };
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.UK);
  private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

  private XlsxMain() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      throw new IllegalArgumentException("Usage: XlsxMain <jsonDir> <template.xlsx> <output.xlsx> "
          + "[environment] [excludedPatternCsv]");
    }

    Path jsonDir = Path.of(args[0]);
    Path template = Path.of(args[1]);
    final Path output = Path.of(args[2]);
    String environment = args.length > 3 ? args[3] : "";
    final List<PathMatcher> exclusions = buildExclusions(args.length > 4 ? args[4] : "");

    if (!Files.isDirectory(jsonDir)) {
      throw new IllegalArgumentException("JSON directory does not exist: " + jsonDir);
    }
    if (!Files.isRegularFile(template)) {
      throw new IllegalArgumentException("Template XLSX does not exist: " + template);
    }

    if (!environment.isBlank()) {
      System.setProperty("ET_ENV", environment);
    }

    try (FileInputStream input = new FileInputStream(template.toFile());
         Workbook workbook = new XSSFWorkbook(input)) {
      Map<String, List<Map<String, Object>>> rowsBySheet = readJsonRows(jsonDir, exclusions);
      for (Map.Entry<String, List<Map<String, Object>>> entry : rowsBySheet.entrySet()) {
        Sheet sheet = workbook.getSheet(entry.getKey());
        if (sheet != null) {
          writeSheet(sheet, entry.getValue());
        }
      }

      Files.createDirectories(output.toAbsolutePath().getParent());
      try (FileOutputStream out = new FileOutputStream(output.toFile())) {
        workbook.write(out);
      }
    }
  }

  private static List<PathMatcher> buildExclusions(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    List<PathMatcher> matchers = new ArrayList<>();
    for (String pattern : csv.split(",")) {
      String trimmed = pattern.trim();
      if (!trimmed.isEmpty()) {
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
      }
    }
    return matchers;
  }

  private static Map<String, List<Map<String, Object>>> readJsonRows(Path root, List<PathMatcher> exclusions)
      throws Exception {
    Map<String, List<Map<String, Object>>> result = new HashMap<>();
    try (Stream<Path> paths = Files.walk(root)) {
      List<Path> files = paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .filter(path -> exclusions.stream().noneMatch(matcher -> matchesAnySegment(root.relativize(path), matcher)))
          .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
          .toList();

      for (Path file : files) {
        Path relative = root.relativize(file);
        String sheetName = sheetName(relative);
        String json = substituteEnvironment(Files.readString(file, StandardCharsets.UTF_8));
        result.computeIfAbsent(sheetName, ignored -> new ArrayList<>()).addAll(MAPPER.readValue(json, ROWS));
      }
    }
    return result;
  }

  private static boolean matchesAnySegment(Path relative, PathMatcher matcher) {
    for (Path segment : relative) {
      if (matcher.matches(segment)) {
        return true;
      }
    }
    return matcher.matches(relative);
  }

  private static String stripJson(String filename) {
    return filename.substring(0, filename.length() - ".json".length());
  }

  private static String sheetName(Path relative) {
    if (relative.getNameCount() == 1) {
      return stripJson(relative.getFileName().toString());
    }
    if (relative.getNameCount() == 2) {
      return stripJson(relative.getFileName().toString());
    }
    return relative.getName(1).toString();
  }

  private static String substituteEnvironment(String value) {
    var matcher = ENV_VAR_PATTERN.matcher(value);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String variable = matcher.group(1);
      String replacement = Optional.ofNullable(System.getenv(variable))
          .orElse(System.getProperty(variable, matcher.group(0)));
      matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static void writeSheet(Sheet sheet, List<Map<String, Object>> rows) {
    List<String> headers = headers(sheet);
    clearData(sheet);
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      Row row = sheet.createRow(rowIndex + 3);
      Map<String, Object> data = rows.get(rowIndex);
      for (int column = 0; column < headers.size(); column++) {
        Object value = data.get(headers.get(column));
        if (value != null) {
          writeCell(row.createCell(column), value);
        }
      }
    }
  }

  private static List<String> headers(Sheet sheet) {
    Row headerRow = sheet.getRow(2);
    List<String> result = new ArrayList<>();
    if (headerRow == null) {
      return result;
    }
    for (Cell cell : headerRow) {
      String value = cell.getStringCellValue();
      if (value != null && !value.isBlank()) {
        result.add(value);
      }
    }
    return result;
  }

  private static void clearData(Sheet sheet) {
    for (int i = sheet.getLastRowNum(); i >= 3; i--) {
      Row row = sheet.getRow(i);
      if (row != null) {
        sheet.removeRow(row);
      }
    }
  }

  private static void writeCell(Cell cell, Object value) {
    if (value instanceof Number number) {
      cell.setCellValue(number.doubleValue());
      return;
    }
    String text = value.toString();
    if (("LiveFrom".equals(header(cell)) || "LiveTo".equals(header(cell))) && text.matches("\\d{2}/\\d{2}/\\d{4}")) {
      LocalDate date = LocalDate.parse(text, DATE_FORMATTER);
      cell.setCellValue(java.util.Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
      CellStyle style = cell.getSheet().getWorkbook().createCellStyle();
      style.setDataFormat(cell.getSheet().getWorkbook().createDataFormat().getFormat("dd/mm/yyyy"));
      cell.setCellStyle(style);
      return;
    }
    if (text.matches("-?\\d+(\\.\\d+)?")) {
      try {
        cell.setCellValue(new BigDecimal(text).doubleValue());
        return;
      } catch (NumberFormatException ignored) {
        // Leave as a string below.
      }
    }
    cell.setCellValue(text);
  }

  private static String header(Cell cell) {
    Row headerRow = cell.getSheet().getRow(2);
    if (headerRow == null) {
      return "";
    }
    Cell header = headerRow.getCell(cell.getColumnIndex());
    return header == null ? "" : header.getStringCellValue();
  }
}
