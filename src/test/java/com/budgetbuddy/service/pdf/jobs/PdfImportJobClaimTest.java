package com.budgetbuddy.service.pdf.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.service.pdf.jobs.PdfImportJob.Status;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link PdfImportJobService#claim(String)} contract used for
 * multi-instance worker fanout. Only one ECS task wins for any given
 * jobId; the loser gets {@code false} and skips processing.
 */
class PdfImportJobClaimTest {

    @SuppressWarnings("unchecked")
    private DynamoDbTable<PdfImportJob> table;
    private PdfImportJobService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        final DynamoDbEnhancedClient enhanced = mock(DynamoDbEnhancedClient.class);
        table = mock(DynamoDbTable.class);
        // doReturn instead of when().thenReturn() — needed because
        // Mockito's generic-type inference rejects the typed table here.
        doReturn(table).when(enhanced).table(any(String.class), any());
        svc = new PdfImportJobService(enhanced);
    }

    @Test
    void claim_returnsFalse_whenJobIdNullOrBlank() {
        assertFalse(svc.claim(null));
        assertFalse(svc.claim(""));
        assertFalse(svc.claim("   "));
    }

    @Test
    void claim_returnsFalse_whenJobNotFound() {
        when(table.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(null);
        assertFalse(svc.claim("missing-id"));
    }

    @Test
    void claim_returnsFalse_whenJobAlreadyProcessing() {
        // Status already PROCESSING means another worker won — skip.
        final PdfImportJob existing = job("xyz", Status.PROCESSING);
        when(table.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(existing);
        assertFalse(svc.claim("xyz"));
    }

    @Test
    void claim_returnsFalse_whenJobCompleted() {
        when(table.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(job("xyz", Status.COMPLETED));
        assertFalse(svc.claim("xyz"));
    }

    @Test
    void claim_returnsTrue_whenConditionalPutSucceeds() {
        when(table.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(job("xyz", Status.QUEUED));
        // putItem with conditional expression succeeds silently
        assertTrue(svc.claim("xyz"));
    }

    @Test
    void claim_returnsFalse_whenConditionalCheckFails() {
        // Race lost: between our read and write, another worker won.
        when(table.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(job("xyz", Status.QUEUED));
        doThrow(ConditionalCheckFailedException.builder().message("lost race").build())
                .when(table).putItem(
                        any(software.amazon.awssdk.enhanced.dynamodb.model
                                .PutItemEnhancedRequest.class));
        assertFalse(svc.claim("xyz"));
    }

    @Test
    void claim_returnsFalse_onUnexpectedException_doesNotThrow() {
        // Network blip, throttling, etc. — must not propagate.
        when(table.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(job("xyz", Status.QUEUED));
        doThrow(new RuntimeException("network down"))
                .when(table).putItem(
                        any(software.amazon.awssdk.enhanced.dynamodb.model
                                .PutItemEnhancedRequest.class));
        assertFalse(svc.claim("xyz"));
    }

    private static PdfImportJob job(final String id, final Status status) {
        final PdfImportJob j = new PdfImportJob();
        j.setJobId(id);
        j.setStatus(status.name());
        j.setUserId("u1");
        return j;
    }
}
