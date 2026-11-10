package interview.guide.infrastructure.export;

import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.action.PdfAction;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Link;
import com.itextpdf.layout.element.ListItem;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ResumePdfExportService {

  private static final DeviceRgb INK = new DeviceRgb(31, 41, 55);
  private static final DeviceRgb MUTED = new DeviceRgb(100, 116, 139);
  private static final DeviceRgb BLUE = new DeviceRgb(29, 78, 216);

  public record ResumeView(@NotNull @Valid Header header,
                           @NotNull @Size(max = 5) List<@NotNull @Valid Section> sections) {}

  public record Header(@NotNull @Size(max = 500) String name,
                       @NotNull @Size(max = 500) String targetPosition,
                       @NotNull @Size(max = 10) List<@NotNull @Size(max = 500) String> contacts,
                       @NotNull @Size(max = 10) List<@NotNull @Pattern(regexp = "(?i)^https?://[^\\s]+$") @Size(max = 2048) String> links) {}

  public record Section(@NotNull @Size(max = 50) String title,
                        @NotNull @Size(max = 100) List<@NotNull @Valid Entry> items,
                        @NotNull @Size(max = 100) List<@NotNull @Size(max = 500) String> skills) {}

  public record Entry(@NotNull @Size(max = 500) String title,
                      @NotNull @Size(max = 1500) String subtitle,
                      @NotNull @Size(max = 100) String date,
                      @NotNull @Size(max = 10000) String summary,
                      @NotNull @Size(max = 10) List<@NotNull @Pattern(regexp = "(?i)^https?://[^\\s]+$") @Size(max = 2048) String> links,
                      @NotNull @Size(max = 100) List<@NotNull @Size(max = 500) String> tags,
                      @NotNull @Size(max = 100) List<@NotNull @Size(max = 10000) String> highlights) {}

  public byte[] export(ResumeView view) {
    var output = new ByteArrayOutputStream();
    var pdf = new PdfDocument(new PdfWriter(output));
    pdf.getDocumentInfo().setTitle(view.header().name().isBlank() ? "简历" : view.header().name() + " · 简历");
    try (var document = new Document(pdf, PageSize.A4)) {
      PdfExportService.configureFonts(document);
      document.setMargins(40, 45, 40, 45);
      document.setFontSize(10.5f).setFontColor(INK);
      addHeader(document, view.header());
      for (Section section : view.sections()) {
        document.add(line(section.title()).setFontSize(12).setBold().setFontColor(BLUE)
            .setBorderLeft(new SolidBorder(BLUE, 2)).setPaddingLeft(6)
            .setMarginTop(10).setMarginBottom(5).setKeepWithNext(true));
        if (!section.skills().isEmpty()) {
          document.add(line(String.join("  ·  ", section.skills())));
        }
        for (Entry entry : section.items()) {
          addEntry(document, entry);
        }
      }
    }
    return output.toByteArray();
  }

  private static void addHeader(Document document, Header header) {
    var table = new Table(UnitValue.createPercentArray(new float[]{1, 2})).useAllAvailableWidth();
    table.setBorderBottom(new SolidBorder(BLUE, 1.5f)).setMarginBottom(3);
    var name = cell().add(line(header.name().isBlank() ? "你的姓名" : header.name()).setFontSize(22).setBold());
    var details = cell().setTextAlignment(TextAlignment.RIGHT);
    if (!header.targetPosition().isBlank()) {
      details.add(line("求职意向：" + header.targetPosition()).setBold().setFontColor(BLUE));
    }
    if (!header.contacts().isEmpty()) {
      details.add(line(String.join("    ", header.contacts())).setFontSize(9.5f).setFontColor(MUTED));
    }
    header.links().forEach(url -> details.add(link(url).setFontSize(9.5f)));
    table.addCell(name.setPaddingBottom(6)).addCell(details.setPaddingBottom(6));
    document.add(table.setKeepTogether(true));
  }

  private static void addEntry(Document document, Entry entry) {
    var heading = line(entry.title()).setBold();
    if (!entry.subtitle().isBlank()) {
      heading.add(new Text("  ·  " + entry.subtitle()).setFontColor(MUTED).setFontSize(9.5f));
    }
    boolean hasBody = !entry.summary().isBlank() || !entry.links().isEmpty()
        || !entry.tags().isEmpty() || !entry.highlights().isEmpty();
    if (entry.date().isBlank()) {
      document.add(heading.setKeepWithNext(hasBody));
    } else {
      var row = new Table(UnitValue.createPercentArray(new float[]{3, 1})).useAllAvailableWidth();
      row.addCell(cell().add(heading));
      row.addCell(cell().add(line(entry.date()).setTextAlignment(TextAlignment.RIGHT)
          .setFontSize(9).setFontColor(MUTED)));
      document.add(row.setKeepTogether(true).setKeepWithNext(hasBody));
    }
    entry.links().forEach(url -> document.add(link(url)));
    if (!entry.tags().isEmpty()) {
      document.add(line(String.join("  ·  ", entry.tags())).setFontSize(9).setFontColor(MUTED));
    }
    if (!entry.summary().isBlank()) {
      document.add(line(entry.summary()));
    }
    if (!entry.highlights().isEmpty()) {
      var highlights = new com.itextpdf.layout.element.List().setListSymbol("•").setSymbolIndent(5).setMarginLeft(12);
      for (String text : entry.highlights()) {
        var item = new ListItem();
        item.add(line(text));
        highlights.add(item);
      }
      document.add(highlights);
    }
  }

  private static Paragraph line(String text) {
    return new Paragraph(text).setMarginTop(0).setMarginBottom(3).setMultipliedLeading(1.35f);
  }

  private static Paragraph link(String url) {
    return line("").add(new Link(url, PdfAction.createURI(url)).setFontColor(BLUE));
  }

  private static Cell cell() {
    return new Cell().setBorder(Border.NO_BORDER).setPadding(0);
  }
}
