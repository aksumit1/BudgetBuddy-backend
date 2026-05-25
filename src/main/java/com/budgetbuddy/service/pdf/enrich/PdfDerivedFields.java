package com.budgetbuddy.service.pdf.enrich;

import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.util.Locale;

/**
 * Pure descriptor-derived field helpers extracted from PDFImportService.
 *
 * <h3>Why this exists</h3>
 *
 * PDFImportService is a 10K-line monolith with 320+ methods. This class
 * is the start of pulling out self-contained derivation logic — the
 * methods here have no collaborator dependencies, take a
 * ParsedTransaction in and return a String. Future passes will lift
 * geo enrichment (~600 LOC) and merchant cleanup (~400 LOC) into sibling
 * classes following the same pattern.
 *
 * <h3>Pinning the contract</h3>
 *
 * Tests live in {@code V2DerivedFieldsUnitTest} (pre-extract location);
 * a copy under {@code PdfDerivedFieldsTest} pins the same contracts on
 * the new home so any future change in EITHER place is caught.
 */
public final class PdfDerivedFields {

    private PdfDerivedFields() { }

    /**
     * Derive a payment-channel value from descriptor + wallet hints. See
     * the original {@code PDFImportService.derivePaymentChannel} for the
     * priority order and rationale. Output values track the Plaid
     * taxonomy: {@code ach | wire | p2p_transfer | online_wallet | atm |
     * check | issuer_payment | issuer_credit | online | in_store |
     * issuer_internal | unknown}.
     */
    public static String derivePaymentChannel(final ParsedTransaction tx) {
        if (tx == null) return null;
        final String desc = tx.getDescription();
        if (desc == null || desc.isBlank()) return null;
        final String upper = desc.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (upper.isEmpty()) return null;

        if (upper.contains("PPD ID") || upper.contains("WEB ID")
                || upper.contains("TEL ID") || upper.contains("CCD ID")
                || upper.contains("ACH CREDIT") || upper.contains("ACH PMT")
                || upper.contains("ACH DEBIT") || upper.matches(".*\\bACH\\b.*")
                || upper.contains("DIRECTPAY") || upper.contains("DIRECT PAY")
                || upper.contains("E-PAYMENT") || upper.contains("EPAYMENT")) {
            return "ach";
        }
        if (upper.contains("PAYMENT THANK YOU") || upper.contains("PAYMENT - THANK YOU")
                || upper.contains("PAYMENT/THANK YOU") || upper.contains("MTC PAYMENT")
                || upper.contains("THANK YOU FOR YOUR PAYMENT")) {
            return "issuer_payment";
        }
        if (upper.matches(".*\\bPLATINUM\\s+[A-Z].*\\bCREDIT\\b.*")
                || upper.contains("STATEMENT CREDIT") || upper.contains("REWARDS CREDIT")
                || upper.contains("MEMBERSHIP REWARDS")) {
            return "issuer_credit";
        }
        if (upper.contains("WIRE TRANSFER") || upper.contains("WIRE PMT")
                || upper.contains("WIRE TXN")) {
            return "wire";
        }
        if (upper.contains("ZELLE") || upper.contains("VENMO")
                || upper.contains("CASHAPP") || upper.contains("CASH APP")
                || upper.contains("PAYPAL ") || upper.contains("PYPL ")) {
            return "p2p_transfer";
        }
        if (tx.getWalletProvider() != null && !tx.getWalletProvider().isBlank()) {
            return "online_wallet";
        }
        if (upper.startsWith("APLPAY ") || upper.startsWith("APL*")
                || upper.contains("GOOGLE PAY ") || upper.startsWith("SQ *")
                || upper.startsWith("TST*")) {
            return "online_wallet";
        }
        if (upper.contains("ATM WITHDRAWAL") || upper.contains("ATM DEPOSIT")
                || upper.contains("CASH WITHDRAWAL")) {
            return "atm";
        }
        if (upper.contains("CHECK") && (upper.contains("PAID") || upper.contains("#"))) {
            return "check";
        }
        if (upper.contains("AUTOPAY") || upper.contains("AUTO PAY")
                || upper.contains("AUTOMATIC PAYMENT")
                || upper.contains("BILLPAY") || upper.contains("BILL PAY")) {
            return "ach";
        }
        if (upper.contains("ONLINE TRANSFER") || upper.contains("EXTRNLTFR")) {
            return "transfer";
        }
        if (upper.contains(".COM") || upper.contains(".NET")
                || upper.contains("HTTP://") || upper.contains("HTTPS://")
                || upper.contains("WWW.") || upper.startsWith("AMZN ")
                || upper.startsWith("AMAZON ") || upper.startsWith("NETFLIX")
                || upper.startsWith("APPLE.COM/")) {
            return "online";
        }
        if (tx.getFlowDirection() == FlowDirection.DEBIT
                && (tx.getLocation() != null || tx.getCity() != null)) {
            return "in_store";
        }
        final String txType = tx.getTransactionType();
        if (txType != null
                && (txType.equals("FEE") || txType.equals("INTEREST")
                        || txType.equals("REFUND") || txType.equals("CREDIT")
                        || txType.equals("PAYMENT"))) {
            return "issuer_internal";
        }
        return "unknown";
    }

    /**
     * Detect "No Annual Fee" disclosure phrasing in the statement lines.
     * Used by PDFImportService to default annualMembershipFee to ZERO
     * (rather than null) when a card explicitly mentions no fee.
     */
    public static boolean statementMentionsNoAnnualFee(final String[] lines) {
        if (lines == null) return false;
        for (final String line : lines) {
            if (line == null) continue;
            final String upper = line.toUpperCase(Locale.ROOT);
            if (upper.contains("NO ANNUAL FEE")
                    || upper.matches(".*ANNUAL\\s+(MEMBERSHIP\\s+)?FEE\\s*[:\\s]\\s*\\$?0(?:\\.0{2})?\\b.*")
                    || upper.contains("ANNUAL FEE: $0")
                    || upper.contains("ANNUAL FEE $0")) {
                return true;
            }
        }
        return false;
    }
}
