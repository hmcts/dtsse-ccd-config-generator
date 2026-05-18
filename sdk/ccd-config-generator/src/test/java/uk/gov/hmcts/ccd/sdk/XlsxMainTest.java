package uk.gov.hmcts.ccd.sdk;

import static org.junit.Assert.assertEquals;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class XlsxMainTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void writesGeneratedJsonRowsToTemplateWorkbook() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path jsonDir = root.resolve("json");
    Path caseTypeDir = jsonDir.resolve("TEST");
    Files.createDirectories(caseTypeDir);
    Files.writeString(caseTypeDir.resolve("CaseType.json"), """
        [
          {
            "ID": "TEST",
            "Name": "${CCD_DEF_NAME}",
            "LiveFrom": "01/02/2026",
            "Retries": "3"
          }
        ]
        """, StandardCharsets.UTF_8);

    System.setProperty("CCD_DEF_NAME", "Test definition");

    Path template = root.resolve("template.xlsx");
    createTemplate(template);

    Path output = root.resolve("output.xlsx");
    XlsxMain.main(new String[] { jsonDir.toString(), template.toString(), output.toString() });

    try (FileInputStream input = new FileInputStream(output.toFile());
         Workbook workbook = new XSSFWorkbook(input)) {
      Row row = workbook.getSheet("CaseType").getRow(3);
      assertEquals("TEST", row.getCell(0).getStringCellValue());
      assertEquals("Test definition", row.getCell(1).getStringCellValue());
      assertEquals(CellType.NUMERIC, row.getCell(2).getCellType());
      assertEquals(3.0d, row.getCell(3).getNumericCellValue(), 0.0d);
    }
  }

  private static void createTemplate(Path path) throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Row headerRow = workbook.createSheet("CaseType").createRow(2);
      headerRow.createCell(0).setCellValue("ID");
      headerRow.createCell(1).setCellValue("Name");
      headerRow.createCell(2).setCellValue("LiveFrom");
      headerRow.createCell(3).setCellValue("Retries");
      try (FileOutputStream output = new FileOutputStream(path.toFile())) {
        workbook.write(output);
      }
    }
  }
}
