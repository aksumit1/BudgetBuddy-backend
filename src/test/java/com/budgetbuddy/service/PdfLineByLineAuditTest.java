package com.budgetbuddy.service;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Exhaustive line-by-line audit across every PDF.
 *
 * For each file:
 *   • Card details: institution, brand, last-4, account type, holder, points,
 *     reward points balance, interest paid (this period + YTD), AutoPay state,
 *     credit limit, available credit
 *   • Per-transaction completeness: date, amount, description, merchant,
 *     username, category, type, direction, card last-4
 *   • Statement-level math: declared totals vs parsed sums (debits, credits)
 *
 * Aggregates:
 *   • Per-bank totals across all that bank's statements
 *   • Grand total across all 42 statements
 *
 * Output:
 *   /tmp/pdf_line_by_line_per_file.csv      — one row per file
 *   /tmp/pdf_line_by_line_per_tx.csv        — one row per transaction
 *   /tmp/pdf_line_by_line_per_bank.csv      — one row per bank
 *   /tmp/pdf_line_by_line_grand_total.csv   — one row total
 */
@EnabledIfSystemProperty(named = "pdf.lbl.dir", matches = ".+")
class PdfLineByLineAuditTest {

    private static final BigDecimal TOLERANCE = new BigDecimal("1.00");

    @Test
    void audit() throws Exception {
        final String dir = System.getProperty("pdf.lbl.dir");
        final File[] pdfs = new File(dir).listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));
        final PDFImportService svc = newSvc();

        final java.nio.file.Path perFile = Paths.get(
                System.getProperty("pdf.lbl.perfile", "/tmp/pdf_line_by_line_per_file.csv"));
        final java.nio.file.Path perTx = Paths.get(
                System.getProperty("pdf.lbl.pertx", "/tmp/pdf_line_by_line_per_tx.csv"));
        final java.nio.file.Path perBank = Paths.get(
                System.getProperty("pdf.lbl.perbank", "/tmp/pdf_line_by_line_per_bank.csv"));
        final java.nio.file.Path grand = Paths.get(
                System.getProperty("pdf.lbl.grand", "/tmp/pdf_line_by_line_grand_total.csv"));
        Files.createDirectories(perFile.getParent());

        final List<FileReport> rows = new ArrayList<>();
        try (BufferedWriter txw = new BufferedWriter(new FileWriter(perTx.toFile()))) {
            txw.write("file,institution,last4,row,date,amount,direction,description,merchant,"
                    + "location,username,category,type,card_last_four,currency,fx_orig_code,fx_orig_amount,"
                    + "fx_rate,wallet,date_ok,amount_ok,desc_ok,direction_ok\n");
            for (final File pdf : pdfs) {
                rows.add(auditOne(svc, pdf, txw));
            }
        }

        writePerFileReport(perFile.toFile(), rows);
        writePerBankReport(perBank.toFile(), rows);
        writeGrandReport(grand.toFile(), rows);

        // Console summary
        System.out.println();
        System.out.println("============================================================");
        System.out.println("                LINE-BY-LINE EXHAUSTIVE AUDIT");
        System.out.println("============================================================");
        System.out.printf("%-58s | %-15s | %-7s | %-25s | %3s | %8s | %8s | %5s%n",
                "FILE", "INSTITUTION", "LAST4", "HOLDER", "TX", "DEBIT", "CREDIT", "ISSUES");
        System.out.println("-".repeat(140));
        int totalIssues = 0;
        for (final FileReport r : rows) {
            System.out.printf("%-58s | %-15s | %-7s | %-25s | %3d | %8s | %8s | %5d%n",
                    trunc(r.file, 58),
                    trunc(r.institution, 15),
                    nz(r.last4),
                    trunc(nz(r.holder), 25),
                    r.txCount,
                    bd(r.parsedDebitSum),
                    bd(r.parsedCreditSum),
                    r.issues.size());
            totalIssues += r.issues.size();
            for (final String issue : r.issues) System.out.println("        - " + issue);
        }

        System.out.println();
        System.out.println("=== BY BANK ===");
        final Map<String, BankAgg> banks = aggregateByBank(rows);
        System.out.printf("%-25s | %3s | %4s | %12s | %12s | %8s%n",
                "BANK", "FIL", "TX", "DEBIT SUM", "CREDIT SUM", "BAD MATH");
        for (final Map.Entry<String, BankAgg> e : banks.entrySet()) {
            System.out.printf("%-25s | %3d | %4d | %12s | %12s | %8d%n",
                    e.getKey(), e.getValue().files, e.getValue().txCount,
                    bd(e.getValue().debitSum), bd(e.getValue().creditSum),
                    e.getValue().mathFails);
        }

        System.out.println();
        System.out.println("=== GRAND TOTAL ===");
        BigDecimal gDebit = BigDecimal.ZERO, gCredit = BigDecimal.ZERO;
        int gTx = 0, gFiles = 0, gMath = 0, gIncomplete = 0;
        for (final FileReport r : rows) {
            gDebit = gDebit.add(r.parsedDebitSum);
            gCredit = gCredit.add(r.parsedCreditSum);
            gTx += r.txCount;
            gFiles++;
            if (r.mathFailed) gMath++;
            gIncomplete += r.incompleteFields;
        }
        System.out.printf("Files: %d | Tx: %d | Debit sum: $%s | Credit sum: $%s | Math fails: %d | Incomplete fields: %d | Issues: %d%n",
                gFiles, gTx, gDebit.toPlainString(), gCredit.toPlainString(),
                gMath, gIncomplete, totalIssues);

        System.out.println();
        System.out.println("Per-file CSV:   " + perFile);
        System.out.println("Per-tx CSV:     " + perTx);
        System.out.println("Per-bank CSV:   " + perBank);
        System.out.println("Grand total:    " + grand);
    }

    // ---- Per-file audit ----

    private FileReport auditOne(
            final PDFImportService svc, final File pdf, final BufferedWriter txw) throws Exception {
        final FileReport r = new FileReport();
        r.file = pdf.getName();
        try (FileInputStream in = new FileInputStream(pdf)) {
            final ImportResult result = svc.parsePDF(in, pdf.getName(), "audit-user", null);
            final DetectedAccount a = result.getDetectedAccount();
            if (a != null) {
                r.institution = nz(a.getInstitutionName());
                r.last4 = nz(a.getAccountNumber());
                r.holder = nz(a.getAccountHolderName());
                r.accountType = nz(a.getAccountType());
                r.accountSubtype = nz(a.getAccountSubtype());
                r.cardName = nz(a.getAccountName());
            }
            r.statementDate = result.getStatementDate();
            r.statementStart = result.getStatementStartDate();
            r.statementEnd = result.getStatementEndDate();
            r.newBalance = result.getNewBalance();
            r.previousBalance = result.getPreviousBalance();
            r.creditLimit = result.getCreditLimit();
            r.availableCredit = result.getAvailableCredit();
            r.minimumPaymentDue = result.getMinimumPaymentDue();
            r.paymentDueDate = result.getPaymentDueDate();
            r.purchasesTotal = result.getPurchasesTotal();
            r.paymentsCreditsTotal = result.getPaymentsAndCreditsTotal();
            r.feesTotal = result.getFeesChargedTotal();
            r.interestPaid = result.getInterestChargedTotal();
            r.ytdInterest = result.getYtdInterestCharged();
            r.ytdFees = result.getYtdFeesCharged();
            r.rewardPoints = result.getRewardPoints();
            r.pointsEarnedThisPeriod = result.getPointsEarnedThisPeriod();
            r.pointsBalance = result.getPointsBalance();
            r.cashBackBalance = result.getCashBackBalance();
            r.autoPayEnabled = result.getAutoPayEnabled();
            r.nextAutoPayAmount = result.getNextAutoPayAmount();
            r.annualMembershipFee = result.getAnnualMembershipFee();
            r.foreignTransactionFeePercent = result.getForeignTransactionFeePercent();

            // ---- Card-detail completeness checks ----
            if (r.institution.isEmpty()) r.issues.add("MISSING institution");
            if (r.last4.isEmpty()) r.issues.add("MISSING last-4");
            if (r.holder.isEmpty()) r.issues.add("MISSING account holder");
            if (r.statementDate == null) r.issues.add("MISSING statement date");
            if (r.newBalance == null) r.issues.add("MISSING new balance");

            // ---- Per-tx audit ----
            int row = 0;
            for (final ParsedTransaction t : result.getTransactions()) {
                row++;
                final boolean dateOk = t.getDate() != null;
                final boolean amountOk = t.getAmount() != null && t.getAmount().signum() != 0;
                final boolean descOk = t.getDescription() != null && !t.getDescription().isBlank();
                final boolean dirOk = t.getFlowDirection() != null;
                if (!dateOk) { r.incompleteFields++; r.issues.add("tx#" + row + " missing date"); }
                if (!amountOk) { r.incompleteFields++; r.issues.add("tx#" + row + " missing amount"); }
                if (!descOk) { r.incompleteFields++; r.issues.add("tx#" + row + " missing description"); }
                if (!dirOk) { r.incompleteFields++; r.issues.add("tx#" + row + " missing flow direction"); }
                if (t.getCategoryPrimary() == null || t.getCategoryPrimary().isBlank()) r.txWithoutCategory++;
                if (t.getMerchantName() == null || t.getMerchantName().isBlank()) r.txWithoutMerchant++;
                final BigDecimal amt = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount().abs();
                if (t.getFlowDirection() == FlowDirection.CREDIT) {
                    r.parsedCreditSum = r.parsedCreditSum.add(amt);
                } else {
                    r.parsedDebitSum = r.parsedDebitSum.add(amt);
                }
                txw.write(String.join(",",
                        csv(r.file), csv(r.institution), csv(r.last4), String.valueOf(row),
                        csv(String.valueOf(t.getDate())),
                        csv(String.valueOf(t.getAmount())),
                        csv(t.getFlowDirection() == null ? "" : t.getFlowDirection().name()),
                        csv(t.getDescription()),
                        csv(t.getMerchantName()),
                        csv(t.getLocation()),
                        csv(t.getUserName()),
                        csv(t.getCategoryPrimary()),
                        csv(t.getTransactionType()),
                        csv(t.getCardLastFour()),
                        csv(t.getCurrencyCode()),
                        csv(t.getOriginalCurrencyCode()),
                        csv(t.getOriginalAmount() == null ? "" : t.getOriginalAmount().toPlainString()),
                        csv(t.getExchangeRate() == null ? "" : t.getExchangeRate().toPlainString()),
                        csv(t.getWalletProvider()),
                        String.valueOf(dateOk), String.valueOf(amountOk),
                        String.valueOf(descOk), String.valueOf(dirOk)) + "\n");
            }
            r.txCount = row;

            // ---- Math reconciliation ----
            final BigDecimal expectedDebit = sumNonNullAbs(
                    r.purchasesTotal, r.feesTotal, r.interestPaid,
                    result.getCashAdvancesTotal(), result.getBalanceTransfersTotal());
            if (expectedDebit != null) {
                final BigDecimal delta = r.parsedDebitSum.subtract(expectedDebit)
                        .setScale(2, RoundingMode.HALF_UP);
                if (delta.abs().compareTo(TOLERANCE) > 0) {
                    r.mathFailed = true;
                    r.issues.add("DEBIT math fail: expected " + expectedDebit
                            + ", parsed " + r.parsedDebitSum + ", delta " + delta);
                }
            }
            if (r.paymentsCreditsTotal != null) {
                final BigDecimal delta = r.parsedCreditSum.subtract(r.paymentsCreditsTotal.abs())
                        .setScale(2, RoundingMode.HALF_UP);
                if (delta.abs().compareTo(TOLERANCE) > 0) {
                    r.mathFailed = true;
                    r.issues.add("CREDIT math fail: expected " + r.paymentsCreditsTotal.abs()
                            + ", parsed " + r.parsedCreditSum + ", delta " + delta);
                }
            }
        } catch (final Exception e) {
            r.issues.add("PARSE_FAILED: " + e.getMessage());
        }
        return r;
    }

    private static BigDecimal sumNonNullAbs(final BigDecimal... values) {
        BigDecimal sum = null;
        for (final BigDecimal v : values) {
            if (v == null) continue;
            sum = (sum == null ? BigDecimal.ZERO : sum).add(v.abs());
        }
        return sum;
    }

    // ---- Aggregations ----

    private static Map<String, BankAgg> aggregateByBank(final List<FileReport> rows) {
        final Map<String, BankAgg> banks = new TreeMap<>();
        for (final FileReport r : rows) {
            final String key = r.institution.isEmpty() ? "(none)" : r.institution;
            final BankAgg a = banks.computeIfAbsent(key, k -> new BankAgg());
            a.files++;
            a.txCount += r.txCount;
            a.debitSum = a.debitSum.add(r.parsedDebitSum);
            a.creditSum = a.creditSum.add(r.parsedCreditSum);
            if (r.mathFailed) a.mathFails++;
            a.totalIssues += r.issues.size();
            a.incompleteFields += r.incompleteFields;
            a.txWithoutCategory += r.txWithoutCategory;
            a.txWithoutMerchant += r.txWithoutMerchant;
        }
        return banks;
    }

    private static void writePerFileReport(final File f, final List<FileReport> rows) throws Exception {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            w.write("file,institution,card_name,last4,holder,account_type,account_subtype,"
                    + "statement_date,start,end,new_balance,previous_balance,"
                    + "credit_limit,available_credit,min_payment_due,payment_due_date,"
                    + "purchases_total,payments_credits_total,fees_total,interest_paid,"
                    + "ytd_interest,ytd_fees,reward_points,points_earned,points_balance,"
                    + "cashback_balance,autopay_enabled,next_autopay_amount,annual_fee,fx_fee_pct,"
                    + "tx_count,parsed_debit_sum,parsed_credit_sum,"
                    + "tx_without_category,tx_without_merchant,incomplete_fields,math_failed,issues\n");
            for (final FileReport r : rows) {
                w.write(String.join(",",
                        csv(r.file), csv(r.institution), csv(r.cardName), csv(r.last4),
                        csv(r.holder), csv(r.accountType), csv(r.accountSubtype),
                        csv(String.valueOf(r.statementDate)), csv(String.valueOf(r.statementStart)),
                        csv(String.valueOf(r.statementEnd)),
                        bd(r.newBalance), bd(r.previousBalance), bd(r.creditLimit), bd(r.availableCredit),
                        bd(r.minimumPaymentDue), csv(String.valueOf(r.paymentDueDate)),
                        bd(r.purchasesTotal), bd(r.paymentsCreditsTotal), bd(r.feesTotal), bd(r.interestPaid),
                        bd(r.ytdInterest), bd(r.ytdFees),
                        nz(String.valueOf(r.rewardPoints)),
                        nz(String.valueOf(r.pointsEarnedThisPeriod)),
                        nz(String.valueOf(r.pointsBalance)),
                        bd(r.cashBackBalance),
                        csv(String.valueOf(r.autoPayEnabled)),
                        bd(r.nextAutoPayAmount), bd(r.annualMembershipFee),
                        bd(r.foreignTransactionFeePercent),
                        String.valueOf(r.txCount),
                        bd(r.parsedDebitSum), bd(r.parsedCreditSum),
                        String.valueOf(r.txWithoutCategory), String.valueOf(r.txWithoutMerchant),
                        String.valueOf(r.incompleteFields), String.valueOf(r.mathFailed),
                        csv(String.join(" | ", r.issues))) + "\n");
            }
        }
    }

    private static void writePerBankReport(final File f, final List<FileReport> rows) throws Exception {
        final Map<String, BankAgg> banks = aggregateByBank(rows);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            w.write("bank,files,tx_count,parsed_debit_sum,parsed_credit_sum,math_fails,incomplete_fields,tx_without_category,tx_without_merchant\n");
            for (final Map.Entry<String, BankAgg> e : banks.entrySet()) {
                w.write(String.join(",",
                        csv(e.getKey()),
                        String.valueOf(e.getValue().files),
                        String.valueOf(e.getValue().txCount),
                        bd(e.getValue().debitSum), bd(e.getValue().creditSum),
                        String.valueOf(e.getValue().mathFails),
                        String.valueOf(e.getValue().incompleteFields),
                        String.valueOf(e.getValue().txWithoutCategory),
                        String.valueOf(e.getValue().txWithoutMerchant)) + "\n");
            }
        }
    }

    private static void writeGrandReport(final File f, final List<FileReport> rows) throws Exception {
        BigDecimal gDebit = BigDecimal.ZERO, gCredit = BigDecimal.ZERO;
        int gTx = 0, gFiles = 0, gMath = 0, gIncomplete = 0, gNoCat = 0, gNoMerch = 0;
        for (final FileReport r : rows) {
            gDebit = gDebit.add(r.parsedDebitSum);
            gCredit = gCredit.add(r.parsedCreditSum);
            gTx += r.txCount; gFiles++;
            if (r.mathFailed) gMath++;
            gIncomplete += r.incompleteFields;
            gNoCat += r.txWithoutCategory;
            gNoMerch += r.txWithoutMerchant;
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            w.write("files,tx_count,total_debit_sum,total_credit_sum,math_failures,incomplete_fields,tx_without_category,tx_without_merchant\n");
            w.write(String.join(",",
                    String.valueOf(gFiles), String.valueOf(gTx),
                    bd(gDebit), bd(gCredit),
                    String.valueOf(gMath), String.valueOf(gIncomplete),
                    String.valueOf(gNoCat), String.valueOf(gNoMerch)) + "\n");
        }
    }

    // ---- Helpers ----

    private static PDFImportService newSvc() {
        final com.budgetbuddy.repository.dynamodb.AccountRepository repo =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        org.mockito.Mockito.when(repo.findByUserId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Collections.emptyList());
        final AccountDetectionService det = new AccountDetectionService(repo, new BalanceExtractor());
        final ImportCategoryParser cat = org.mockito.Mockito.mock(ImportCategoryParser.class);
        org.mockito.Mockito.when(cat.parseCategory(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("Uncategorized");
        final EnhancedPatternMatcher pm = new EnhancedPatternMatcher();
        final com.budgetbuddy.service.pdf.PdfTemplateRegistry reg =
                new com.budgetbuddy.service.pdf.PdfTemplateRegistry();
        try {
            final java.lang.reflect.Field f =
                    com.budgetbuddy.service.pdf.PdfTemplateRegistry.class.getDeclaredField("resourcePattern");
            f.setAccessible(true);
            f.set(reg, "classpath*:pdf-templates/*.yaml");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        reg.init();
        final com.budgetbuddy.service.pdf.v2.PdfTemplateV2Registry v2 =
                new com.budgetbuddy.service.pdf.v2.PdfTemplateV2Registry();
        v2.initForTesting("classpath*:pdf-templates-v2/*.yaml");
        return new PDFImportService(det, cat, pm, null, null, reg, null, null, null, v2);
    }

    private static String csv(final String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
    private static String bd(final BigDecimal v) { return v == null ? "" : v.toPlainString(); }
    private static String nz(final String s) {
        return s == null || "null".equals(s) ? "" : s;
    }
    private static String trunc(final String s, final int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static final class FileReport {
        String file = "", institution = "", last4 = "", holder = "", accountType = "",
                accountSubtype = "", cardName = "";
        java.time.LocalDate statementDate, statementStart, statementEnd, paymentDueDate;
        BigDecimal newBalance, previousBalance, creditLimit, availableCredit, minimumPaymentDue;
        BigDecimal purchasesTotal, paymentsCreditsTotal, feesTotal, interestPaid;
        BigDecimal ytdInterest, ytdFees;
        Long rewardPoints, pointsEarnedThisPeriod, pointsBalance;
        BigDecimal cashBackBalance;
        Boolean autoPayEnabled;
        BigDecimal nextAutoPayAmount, annualMembershipFee, foreignTransactionFeePercent;
        int txCount, incompleteFields, txWithoutCategory, txWithoutMerchant;
        BigDecimal parsedDebitSum = BigDecimal.ZERO, parsedCreditSum = BigDecimal.ZERO;
        boolean mathFailed;
        final List<String> issues = new ArrayList<>();
    }

    private static final class BankAgg {
        int files, txCount, mathFails, totalIssues, incompleteFields, txWithoutCategory, txWithoutMerchant;
        BigDecimal debitSum = BigDecimal.ZERO, creditSum = BigDecimal.ZERO;
    }
}
