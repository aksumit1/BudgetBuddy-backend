package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the YAML-driven {@link TransactionExtractor}. Covers the
 * three shape modes that motivated the primitive — single-line, multi-line
 * (Amex 3-line), FX-block stripping — without touching production
 * PDFImportService code.
 */
class TransactionExtractorTest {

    private static final TransactionExtractor EXTRACTOR = new TransactionExtractor();

    private static PdfTemplateV2 template(final PdfTemplateV2.TransactionShape... shapes) {
        final PdfTemplateV2 t = new PdfTemplateV2();
        t.setId("test");
        t.setInstitution("Test Bank");
        t.setTransactions(List.of(shapes));
        return t;
    }

    private static PdfTemplateV2.TransactionShape singleLine(final String name,
            final String regex, final String dateFormat) {
        final PdfTemplateV2.TransactionShape s = new PdfTemplateV2.TransactionShape();
        s.setName(name);
        s.setLineRegex(regex);
        s.setDateFormat(dateFormat);
        return s;
    }

    private static PdfTemplateV2.TransactionShape multiLine(final String name,
            final String startRegex, final String endRegex, final Integer maxLines,
            final String dateFormat) {
        final PdfTemplateV2.TransactionShape s = new PdfTemplateV2.TransactionShape();
        s.setName(name);
        s.setStartRegex(startRegex);
        s.setEndRegex(endRegex);
        s.setMaxLines(maxLines);
        s.setDateFormat(dateFormat);
        return s;
    }

    @Test
    void singleLineShape_extractsBasicTransactions() {
        final PdfTemplateV2 t = template(singleLine("two-date-credit",
                "^(?<date>\\d{1,2}/\\d{1,2})\\s+\\d{1,2}/\\d{1,2}\\s+(?<description>.+?)\\s+\\$?(?<amount>-?\\d+(?:,\\d{3})*\\.\\d{2})\\s*$",
                "M/d/yyyy"));
        final String fixture = String.join("\n",
                "12/05 12/06 SAFEWAY #1444 BELLEVUE WA $14.27",
                "12/10 12/11 STARBUCKS SEATTLE WA $5.95");
        final List<TransactionExtractor.ExtractedTransaction> out =
                EXTRACTOR.extract(t, fixture);
        assertEquals(2, out.size());
        assertEquals("SAFEWAY #1444 BELLEVUE WA", out.getFirst().description);
        assertEquals(0, new BigDecimal("14.27").compareTo(out.getFirst().amount));
        assertEquals("STARBUCKS SEATTLE WA", out.get(1).description);
    }

    @Test
    void multiLineShape_amexThreeLineTransaction() {
        // Amex prints transactions like:
        //   "12/19/25 GOOGLE *YOUTUBE MUSIC"
        //   "G.CO/HELPPAY# CA"
        //   "$18.72"
        // start_regex captures date + first-line desc; end_regex captures amount;
        // the middle line becomes part of description.
        final PdfTemplateV2 t = template(multiLine("amex-three-line",
                "^(?<date>\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+(?<description>.+)$",
                "^\\$(?<amount>-?\\d+(?:,\\d{3})*\\.\\d{2})\\s*$",
                3, "M/d/yy"));
        final String fixture = String.join("\n",
                "12/19/25 GOOGLE *YOUTUBE MUSIC",
                "G.CO/HELPPAY# CA",
                "$18.72");
        final List<TransactionExtractor.ExtractedTransaction> out =
                EXTRACTOR.extract(t, fixture);
        assertEquals(1, out.size());
        assertEquals(LocalDate.of(2025, 12, 19), out.getFirst().date);
        assertTrue(out.getFirst().description.contains("YOUTUBE MUSIC"));
        assertTrue(out.getFirst().description.contains("G.CO/HELPPAY"),
                "intermediate line must be folded into description");
        assertEquals(0, new BigDecimal("18.72").compareTo(out.getFirst().amount));
    }

    @Test
    void multiLineShape_fxBlockStripped() {
        // Amex appends a foreign-tx info block AFTER the amount:
        //   "12/19/25 PADARIA"
        //   "LISBON PORTUGAL"
        //   "$10.05"
        //   "Exchange Rate 1.1"
        //   "Foreign Spend 9.14"
        // strip_lines_matching must drop those lines so subsequent shapes
        // don't see them as a fresh transaction.
        final PdfTemplateV2.TransactionShape s = new PdfTemplateV2.TransactionShape();
        s.setName("amex-fx");
        s.setStartRegex("^(?<date>\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+(?<description>.+)$");
        s.setEndRegex("^\\$(?<amount>-?\\d+(?:,\\d{3})*\\.\\d{2})\\s*$");
        s.setMaxLines(3);
        s.setDateFormat("M/d/yy");
        s.setStripLinesMatching(List.of(
                "(?i)^Exchange\\s+Rate",
                "(?i)^Foreign\\s+Spend"));
        final PdfTemplateV2 t = template(s);
        final String fixture = String.join("\n",
                "12/19/25 PADARIA",
                "LISBON PORTUGAL",
                "$10.05",
                "Exchange Rate 1.1",
                "Foreign Spend 9.14",
                "12/20/25 STARBUCKS BELLEVUE WA",
                "ID#1234",
                "$5.95");
        final List<TransactionExtractor.ExtractedTransaction> out =
                EXTRACTOR.extract(t, fixture);
        assertEquals(2, out.size(), "FX info block must NOT count as a third tx");
        assertEquals(0, new BigDecimal("10.05").compareTo(out.getFirst().amount));
        assertEquals(0, new BigDecimal("5.95").compareTo(out.get(1).amount));
    }

    @Test
    void singleLineShape_belowMinAmount_isSkipped() {
        final PdfTemplateV2.TransactionShape s = singleLine("min-amt",
                "^(?<date>\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+(?<description>.+?)\\s+\\$(?<amount>\\d+\\.\\d{2})\\s*$",
                "M/d/yyyy");
        s.setMinAmount(new BigDecimal("1.00"));
        final PdfTemplateV2 t = template(s);
        final String fixture = String.join("\n",
                "12/05/2025 LITTLE SALE $0.50",
                "12/06/2025 REAL TX $5.00");
        final List<TransactionExtractor.ExtractedTransaction> out =
                EXTRACTOR.extract(t, fixture);
        assertEquals(1, out.size());
        assertEquals(0, new BigDecimal("5.00").compareTo(out.getFirst().amount));
    }

    @Test
    void emptyTransactionsListReturnsEmpty() {
        final PdfTemplateV2 t = new PdfTemplateV2();
        t.setId("x"); t.setInstitution("y");
        assertEquals(0, EXTRACTOR.extract(t, "anything").size());
    }

    @Test
    void shapeOrderingDeterminesPrecedence() {
        // Two shapes match the same line — first wins.
        final PdfTemplateV2.TransactionShape a = singleLine("strict",
                "^(?<date>\\d{1,2}/\\d{1,2}/\\d{4})\\s+(?<description>FOO)\\s+\\$(?<amount>\\d+\\.\\d{2})$",
                "M/d/yyyy");
        final PdfTemplateV2.TransactionShape b = singleLine("loose",
                "^(?<date>\\d{1,2}/\\d{1,2}/\\d{4})\\s+(?<description>.+?)\\s+\\$(?<amount>\\d+\\.\\d{2})$",
                "M/d/yyyy");
        final PdfTemplateV2 t = template(a, b);
        final List<TransactionExtractor.ExtractedTransaction> out =
                EXTRACTOR.extract(t, "12/05/2025 FOO $1.00");
        assertEquals(1, out.size());
        assertEquals("strict", out.getFirst().shapeName,
                "first-matching shape must win");
    }

    @Test
    void noNamedGroupsFailsGracefullyAsNoMatch() {
        // Regex compiles but has no named "date"/"description"/"amount" groups.
        // Extractor must drop the candidate, not throw.
        final PdfTemplateV2 t = template(singleLine("bad",
                "^\\d+/\\d+/\\d+ .+ \\$\\d+\\.\\d{2}$", "M/d/yyyy"));
        final List<TransactionExtractor.ExtractedTransaction> out =
                EXTRACTOR.extract(t, "12/05/2025 FOO $1.00");
        assertEquals(0, out.size());
    }

    @Test
    void multiLineShape_amountNotFoundWithinMaxLinesYieldsNoTx() {
        final PdfTemplateV2 t = template(multiLine("tight",
                "^(?<date>\\d{1,2}/\\d{1,2}/\\d{4})\\s+(?<description>.+)$",
                "^\\$(?<amount>\\d+\\.\\d{2})$",
                2, "M/d/yyyy"));
        final String fixture = String.join("\n",
                "12/05/2025 DESC",
                "more desc",
                "more desc 2",
                "more desc 3",
                "$10.00");
        // Amount is 4 lines below start, but max_lines is 2 — must NOT match.
        assertEquals(0, EXTRACTOR.extract(t, fixture).size());
    }

    @Test
    void extractorIsNullSafe() {
        // Null template, null text — must not throw.
        assertNotNull(EXTRACTOR.extract((PdfTemplateV2) null, "anything"));
        assertNotNull(EXTRACTOR.extract(new PdfTemplateV2(), (String) null));
        assertEquals(0, EXTRACTOR.extract((PdfTemplateV2) null, "anything").size());
    }
}
