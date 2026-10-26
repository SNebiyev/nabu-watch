package watch.nabu.application.reporting;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.application.website.WebsiteHealthView;
import watch.nabu.application.website.WebsiteQueryService;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.ScanLogRepository;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Generates a downloadable PDF compliance report summarising the latest
 * health view for one website. Intended for agencies that forward scan
 * results to their clients.
 *
 * <p>Sources data from {@link WebsiteQueryService#getHealthView(UUID)} — i.e.
 * one website + its monitors + the latest scan per monitor — plus the owning
 * {@link Website} entity for organization / domain metadata.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceReportService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final Font TITLE_FONT =
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24, new Color(18, 32, 56));
    private static final Font H1_FONT =
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(18, 32, 56));
    private static final Font H2_FONT =
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(18, 32, 56));
    private static final Font BODY_FONT =
            FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
    private static final Font BODY_BOLD =
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
    private static final Font SMALL_FONT =
            FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);
    private static final Font GRADE_FONT =
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 72, new Color(34, 139, 90));

    private final WebsiteQueryService websiteQueryService;
    private final ScanLogRepository scanLogRepository;

    /**
     * Extracts the host portion of a URL for the filename, falling back to a
     * sanitised copy of the full string if parsing fails.
     */
    public static String domainOf(String url) {
        if (url == null || url.isBlank()) return "website";
        try {
            String candidate = url.contains("://") ? url : "https://" + url;
            String host = URI.create(candidate).getHost();
            if (host != null && !host.isBlank()) {
                return host.replaceFirst("^www\\.", "");
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        return url.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    /**
     * Bucketing (same rule as {@code WebsiteQueryService.getHealthView}):
     * <ul>
     *   <li>no lastCheckedAt → {@code PENDING}</li>
     *   <li>CRITICAL/HIGH → {@code FAILING}</li>
     *   <li>MEDIUM/INFO/LOW/null → {@code PASSING}</li>
     * </ul>
     */
    private static String bucketStatus(Severity sev, java.time.Instant lastCheckedAt) {
        if (lastCheckedAt == null) return "PENDING";
        return sev != null && sev.isFailing() ? "FAILING" : "PASSING";
    }

    // ── sections ───────────────────────────────────────────────────────────

    private static void addMetaRow(PdfPTable table, String key, String value) {
        PdfPCell k = new PdfPCell(new Phrase(key, BODY_BOLD));
        k.setBorder(Rectangle.NO_BORDER);
        k.setPadding(6f);
        table.addCell(k);
        PdfPCell v = new PdfPCell(new Phrase(value != null ? value : "—", BODY_FONT));
        v.setBorder(Rectangle.NO_BORDER);
        v.setPadding(6f);
        table.addCell(v);
    }

    private static void addKv(PdfPTable table, String k, String v) {
        PdfPCell kc = new PdfPCell(new Phrase(k, BODY_BOLD));
        kc.setBorder(Rectangle.NO_BORDER);
        kc.setPadding(4f);
        table.addCell(kc);
        PdfPCell vc = new PdfPCell(new Phrase(v, BODY_FONT));
        vc.setBorder(Rectangle.NO_BORDER);
        vc.setPadding(4f);
        table.addCell(vc);
    }

    private static void addHeaderCell(PdfPTable table, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, BODY_BOLD));
        c.setBackgroundColor(new Color(230, 236, 245));
        c.setPadding(6f);
        table.addCell(c);
    }

    private static void addBodyCell(PdfPTable table, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, BODY_FONT));
        c.setPadding(5f);
        table.addCell(c);
    }

    private static void addSpacer(Document doc, float height) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(height);
        try {
            doc.add(p);
        } catch (DocumentException ignored) {
        }
    }

    // ── cell / row helpers ─────────────────────────────────────────────────

    private static String stringOr(Map<String, Object> json, String key, String fallback) {
        if (json == null) return fallback;
        Object v = json.get(key);
        if (v == null) return fallback;
        String s = v.toString();
        return s.isBlank() ? fallback : s;
    }

    /**
     * Helper exposing today's UTC date for the filename (used by controller).
     */
    public static String todayIso() {
        return DATE_FMT.format(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * Builds a PDF compliance report for the given website.
     *
     * <p>The caller is responsible for loading the {@link Website} and enforcing
     * org ownership (typically via {@code WebsiteService.requireAccess}); this
     * service does not re-verify.
     *
     * @param website the target website (already loaded + authorised by caller)
     * @return the PDF as a byte array (starts with {@code %PDF-} magic)
     */
    public byte[] generatePdf(Website website) {
        UUID websiteId = website.getId();
        WebsiteHealthView view = websiteQueryService.getHealthView(websiteId);
        String orgName = website.getOrganization().getName();

        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        Document doc = new Document(PageSize.A4, 48, 48, 60, 60);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new FooterEvent());
        doc.open();

        writeCoverPage(doc, website, view, orgName);
        doc.newPage();
        writeSummary(doc, view);
        writeMonitorTable(doc, view);
        writeIssues(doc, view);

        doc.close();
        writer.close();

        log.info("Generated compliance PDF: websiteId={} size={}B", websiteId, out.size());
        return out.toByteArray();
    }

    private void writeCoverPage(Document doc, Website website, WebsiteHealthView view, String orgName) {
        addSpacer(doc, 60);
        Paragraph logo = new Paragraph("NABU WATCH", TITLE_FONT);
        logo.setAlignment(Element.ALIGN_CENTER);
        doc.add(logo);

        Paragraph subtitle = new Paragraph("Compliance Report", H1_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(60);
        doc.add(subtitle);

        String grade = view.complianceGrade() != null ? view.complianceGrade() : "—";
        Paragraph gradePara = new Paragraph(grade, GRADE_FONT);
        gradePara.setAlignment(Element.ALIGN_CENTER);
        doc.add(gradePara);

        Paragraph gradeLabel = new Paragraph("Overall Compliance Grade", BODY_FONT);
        gradeLabel.setAlignment(Element.ALIGN_CENTER);
        gradeLabel.setSpacingAfter(50);
        doc.add(gradeLabel);

        PdfPTable meta = new PdfPTable(2);
        meta.setWidthPercentage(80);
        meta.setWidths(new float[]{1f, 2f});
        meta.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        meta.getDefaultCell().setPadding(6f);

        addMetaRow(meta, "Website URL", website.getUrl());
        addMetaRow(meta, "Domain", domainOf(website.getUrl()));
        addMetaRow(meta, "Organization", orgName != null ? orgName : "—");
        addMetaRow(meta, "Region", website.getRegionCode() != null ? website.getRegionCode() : "—");
        addMetaRow(meta, "Compliance Score",
                view.complianceScore() != null ? view.complianceScore() + " / 100" : "—");
        addMetaRow(meta, "Report Generated", TS_FMT.format(view.evaluatedAt()));

        doc.add(meta);
    }

    private void writeSummary(Document doc, WebsiteHealthView view) {
        Paragraph h = new Paragraph("Summary", H1_FONT);
        h.setSpacingAfter(12);
        doc.add(h);

        List<WebsiteHealthView.MonitorSummary> monitors = view.monitors();
        int total = monitors.size();
        Map<Severity, Long> bySeverity = monitors.stream()
                .filter(m -> m.severity() != null)
                .collect(Collectors.groupingBy(WebsiteHealthView.MonitorSummary::severity, Collectors.counting()));
        Map<watch.nabu.domain.model.Monitor.MonitorType, Long> byType = monitors.stream()
                .collect(Collectors.groupingBy(WebsiteHealthView.MonitorSummary::monitorType, Collectors.counting()));

        // last-scan per type
        Map<watch.nabu.domain.model.Monitor.MonitorType, java.time.Instant> lastScanPerType = new EnumMap<>(watch.nabu.domain.model.Monitor.MonitorType.class);
        for (var m : monitors) {
            if (m.lastCheckedAt() == null) continue;
            lastScanPerType.merge(m.monitorType(), m.lastCheckedAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1f, 1f});
        table.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        table.getDefaultCell().setPadding(4f);

        addKv(table, "Total monitors", String.valueOf(total));
        addKv(table, "SSL monitors", String.valueOf(byType.getOrDefault(watch.nabu.domain.model.Monitor.MonitorType.SSL, 0L)));
        addKv(table, "Compliance monitors", String.valueOf(byType.getOrDefault(watch.nabu.domain.model.Monitor.MonitorType.COMPLIANCE, 0L)));
        addKv(table, "Critical", String.valueOf(bySeverity.getOrDefault(Severity.CRITICAL, 0L)));
        addKv(table, "High", String.valueOf(bySeverity.getOrDefault(Severity.HIGH, 0L)));
        addKv(table, "Medium", String.valueOf(bySeverity.getOrDefault(Severity.MEDIUM, 0L)));
        addKv(table, "Info", String.valueOf(bySeverity.getOrDefault(Severity.INFO, 0L)));
        for (var entry : lastScanPerType.entrySet()) {
            addKv(table, "Last " + entry.getKey().name() + " scan", TS_FMT.format(entry.getValue()));
        }

        table.setSpacingAfter(18);
        doc.add(table);
    }

    private void writeMonitorTable(Document doc, WebsiteHealthView view) {
        Paragraph h = new Paragraph("Monitors", H1_FONT);
        h.setSpacingAfter(8);
        doc.add(h);

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1.2f, 2.2f, 1.2f, 2.2f, 1.2f});

        addHeaderCell(table, "Type");
        addHeaderCell(table, "Sub-type");
        addHeaderCell(table, "Severity");
        addHeaderCell(table, "Last checked");
        addHeaderCell(table, "Status");

        for (var m : view.monitors()) {
            addBodyCell(table, m.monitorType().name());
            addBodyCell(table, m.subType() != null ? m.subType().name() : "—");
            addBodyCell(table, m.severity() != null ? m.severity().name() : "—");
            addBodyCell(table, m.lastCheckedAt() != null ? TS_FMT.format(m.lastCheckedAt()) : "—");
            addBodyCell(table, bucketStatus(m.severity(), m.lastCheckedAt()));
        }

        table.setSpacingAfter(18);
        doc.add(table);
    }

    private void writeIssues(Document doc, WebsiteHealthView view) {
        List<WebsiteHealthView.MonitorSummary> issues = view.monitors().stream()
                .filter(m -> m.severity() != null && m.severity().isFailing())
                .toList();

        Paragraph h = new Paragraph("Issues Requiring Attention", H1_FONT);
        h.setSpacingAfter(8);
        doc.add(h);

        if (issues.isEmpty()) {
            Paragraph none = new Paragraph("No high or critical issues detected.", BODY_FONT);
            none.setSpacingAfter(12);
            doc.add(none);
            return;
        }

        // Fetch the backing ScanLog for each failing monitor so we can surface
        // the result_json title + remediation.
        List<UUID> monitorIds = issues.stream().map(WebsiteHealthView.MonitorSummary::monitorId).toList();
        Map<UUID, ScanLog> latestByMonitor = scanLogRepository
                .findLatestPerMonitorByWebsiteId(view.websiteId())
                .stream()
                .collect(Collectors.toMap(sl -> sl.getMonitor().getId(), sl -> sl, (a, b) -> a));

        for (var m : issues) {
            ScanLog log = latestByMonitor.get(m.monitorId());
            Map<String, Object> json = log != null ? log.getResultJson() : null;

            String title = stringOr(json, "title",
                    stringOr(json, "issue",
                            m.subType() != null ? m.subType().name() : m.monitorType().name()));
            String remediation = stringOr(json, "remediation",
                    stringOr(json, "remediation_tip",
                            stringOr(json, "recommendation", null)));
            String summaryLine = m.summary();

            PdfPTable box = new PdfPTable(1);
            box.setWidthPercentage(100);
            box.setSpacingBefore(6f);
            box.setSpacingAfter(6f);

            PdfPCell head = new PdfPCell(new Phrase(
                    "[" + m.severity().name() + "] " + title, BODY_BOLD));
            head.setPadding(6f);
            head.setBackgroundColor(new Color(245, 232, 232));
            head.setBorderColor(new Color(200, 170, 170));
            box.addCell(head);

            StringBuilder body = new StringBuilder();
            if (summaryLine != null && !summaryLine.isBlank()) {
                body.append(summaryLine).append('\n');
            }
            if (remediation != null && !remediation.isBlank()) {
                body.append("Recommendation: ").append(remediation);
            }
            if (body.length() == 0) {
                body.append("No additional detail available.");
            }

            PdfPCell bodyCell = new PdfPCell(new Phrase(body.toString(), BODY_FONT));
            bodyCell.setPadding(6f);
            bodyCell.setBorderColor(new Color(200, 170, 170));
            box.addCell(bodyCell);

            doc.add(box);
        }
    }

    /**
     * Footer with page numbers + generation timestamp.
     */
    private static final class FooterEvent extends PdfPageEventHelper {
        private final String generated = TS_FMT.format(java.time.Instant.now());

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Phrase left = new Phrase(
                    "Generated by nabu-watch  •  " + generated, SMALL_FONT);
            Phrase right = new Phrase(
                    "Page " + writer.getPageNumber(), SMALL_FONT);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, left,
                    document.leftMargin(), 30, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, right,
                    document.right(), 30, 0);
        }
    }
}
