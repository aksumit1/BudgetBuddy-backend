package com.budgetbuddy.service.pdf.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link PdfTransactionPersister} contract:
 *
 * <ol>
 *   <li>Each parsed tx goes through createTransactionFromParsedPdf exactly
 *       once — the consolidated create+enrich path (B1 fix).</li>
 *   <li>Per-row failures don't break the batch — counted in tally.failed.</li>
 *   <li>Null/empty input returns a zero-tally without throwing.</li>
 * </ol>
 */
class PdfTransactionPersisterTest {

    @Test
    void persist_callsCreateOncePerRow() {
        final TransactionService svc = mock(TransactionService.class);
        when(svc.createTransactionFromParsedPdf(any(), any(), any(), any()))
                .thenReturn(new TransactionTable());
        final PdfTransactionPersister persister = new PdfTransactionPersister(svc);
        final UserTable user = newUser();

        final PdfTransactionPersister.Tally tally = persister.persist(
                user, List.of(tx("STARBUCKS"), tx("WHOLE FOODS")), "PDF", "march.pdf");

        assertEquals(2, tally.total());
        assertEquals(2, tally.created());
        assertEquals(0, tally.failed());
        verify(svc, times(2)).createTransactionFromParsedPdf(
                eq(user), any(), any(), eq("march.pdf"));
    }

    @Test
    void persist_oneRowFailing_doesNotBreakBatch() {
        final TransactionService svc = mock(TransactionService.class);
        when(svc.createTransactionFromParsedPdf(any(), any(), any(), any()))
                .thenReturn(new TransactionTable())                  // 1: ok
                .thenThrow(new RuntimeException("simulated DDB throttle"))  // 2: fail
                .thenReturn(new TransactionTable());                 // 3: ok
        final PdfTransactionPersister persister = new PdfTransactionPersister(svc);

        final PdfTransactionPersister.Tally tally = persister.persist(
                newUser(), List.of(tx("A"), tx("B"), tx("C")), "PDF", "test.pdf");

        assertEquals(3, tally.total());
        assertEquals(2, tally.created());
        assertEquals(1, tally.failed());
    }

    @Test
    void persist_nullList_returnsZeroTally() {
        final PdfTransactionPersister persister = new PdfTransactionPersister(
                mock(TransactionService.class));
        final PdfTransactionPersister.Tally tally = persister.persist(
                newUser(), null, "PDF", "x.pdf");
        assertEquals(0, tally.total());
        assertEquals(0, tally.created());
        assertEquals(0, tally.failed());
    }

    @Test
    void persist_emptyList_returnsZeroTally() {
        final PdfTransactionPersister persister = new PdfTransactionPersister(
                mock(TransactionService.class));
        final PdfTransactionPersister.Tally tally = persister.persist(
                newUser(), List.of(), "PDF", "x.pdf");
        assertEquals(0, tally.total());
    }

    private static UserTable newUser() {
        final UserTable u = new UserTable();
        u.setUserId("u1");
        u.setEmail("u1@test");
        return u;
    }

    private static PDFImportService.ParsedTransaction tx(final String desc) {
        final PDFImportService.ParsedTransaction t = new PDFImportService.ParsedTransaction();
        t.setDate(LocalDate.of(2026, 5, 1));
        t.setDescription(desc);
        t.setAmount(new BigDecimal("10.00").negate());
        return t;
    }
}
