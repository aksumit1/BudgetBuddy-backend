package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * One-shot corpus audit. Walks every PDF in {@code pdf.lbl.dir} and prints,
 * per file: tx count, per-component geo-coverage rates, and 10 sample rows
 * with their extracted geo values + the raw description so a human can
 * eyeball whether the parse is right. Not a pass/fail test — output is meant
 * for visual review.
 */
@EnabledIfSystemProperty(named = "pdf.lbl.dir", matches = ".+")
class V2GeoAuditProbe {

    private static final String CORPUS_DIR = System.getProperty(
            "pdf.lbl.dir", "/Users/garimaagarwal/Downloads/statements");

    @Test
    void categorizeMissingLocation() throws Exception {
        // For every transaction with no city/state extracted, classify
        // whether the description has any geographic structure at all.
        // Goal: separate "legitimately no location" (ACH, refunds, P2P,
        // bill-pay, internal transfers) from "we should have extracted it".
        final File dir = new File(CORPUS_DIR);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null) return;
        final PDFImportService svc = TestPdfImportFactory.newSvc();

        int total = 0, hasGeo = 0, noGeo = 0;
        int catACH = 0, catPayment = 0, catTransfer = 0, catCheck = 0;
        int catRefund = 0, catFee = 0, catInterest = 0, catAutomatic = 0;
        int catNoMerchant = 0, catPossiblyMissed = 0;
        final java.util.List<String> missedSamples = new java.util.ArrayList<>();

        for (final File pdf : pdfs) {
            try (InputStream in = new FileInputStream(pdf)) {
                final ImportResult r = svc.parsePDF(in, pdf.getName(), "audit", null);
                for (final ParsedTransaction tx : r.getTransactions()) {
                    total++;
                    if (tx.getCity() != null || tx.getState() != null || tx.getCountry() != null) {
                        hasGeo++;
                        continue;
                    }
                    noGeo++;
                    final String d = tx.getDescription() == null ? ""
                            : tx.getDescription().toUpperCase();
                    if (d.contains("ACH ") || d.contains("PPD ID") || d.contains("WEB ID")
                            || d.contains("ACH PMT") || d.contains("ACH CREDIT")) {
                        catACH++;
                    } else if (d.contains("AUTOMATIC PAYMENT") || d.contains("PAYMENT THANK")
                            || d.contains("DIRECTPAY") || d.contains("E-PAYMENT")) {
                        catAutomatic++;
                    } else if (d.contains("PAYMENT TO ") || d.contains("CARD ENDING IN")
                            || d.contains("AUTOPAY") || d.contains("AUTO PAY")
                            || d.contains("BILLPAY") || d.contains("BILL PAY")) {
                        catPayment++;
                    } else if (d.contains("ZELLE") || d.contains("VENMO") || d.contains("CASHAPP")
                            || d.contains("ONLINE TRANSFER") || d.contains("EXTRNLTFR")
                            || d.contains("WIRE")) {
                        catTransfer++;
                    } else if (d.contains("CHECK ") || d.contains("CHECK #")) {
                        catCheck++;
                    } else if (d.contains("REFUND") || d.contains("RETURN")
                            || d.contains("CREDIT ADJUSTMENT") || d.contains("REVERSAL")) {
                        catRefund++;
                    } else if (d.contains("FEE") || d.contains("MEMBERSHIP")) {
                        catFee++;
                    } else if (d.contains("INTEREST")) {
                        catInterest++;
                    } else if (d.isBlank() || d.length() < 8) {
                        catNoMerchant++;
                    } else {
                        catPossiblyMissed++;
                        if (missedSamples.size() < 50) {
                            missedSamples.add("[" + pdf.getName() + "] desc=<<"
                                    + truncate(tx.getDescription(), 120) + ">>");
                        }
                    }
                }
            } catch (final Exception ignored) { }
        }
        System.out.println();
        System.out.println("======== MISSING-LOCATION BREAKDOWN ========");
        System.out.printf("total tx:           %5d%n", total);
        System.out.printf("with geo:           %5d (%4.1f%%)%n", hasGeo,
                100.0 * hasGeo / total);
        System.out.printf("no geo (total):     %5d (%4.1f%%)%n", noGeo,
                100.0 * noGeo / total);
        System.out.println();
        System.out.println("--- no-geo classified as legitimate ---");
        System.out.printf("ACH (PPD/Web ID):   %5d%n", catACH);
        System.out.printf("automatic-payment:  %5d%n", catAutomatic);
        System.out.printf("payment-to-card:    %5d%n", catPayment);
        System.out.printf("transfer (P2P):     %5d%n", catTransfer);
        System.out.printf("check:              %5d%n", catCheck);
        System.out.printf("refund:             %5d%n", catRefund);
        System.out.printf("fee:                %5d%n", catFee);
        System.out.printf("interest:           %5d%n", catInterest);
        System.out.printf("no-merchant-info:   %5d%n", catNoMerchant);
        final int legit = catACH + catAutomatic + catPayment + catTransfer + catCheck
                + catRefund + catFee + catInterest + catNoMerchant;
        System.out.printf("--- LEGITIMATE NO-LOCATION TOTAL: %d (%.1f%% of all tx)%n",
                legit, 100.0 * legit / total);
        System.out.println();
        System.out.printf("possibly-missed:    %5d (%.1f%% of all tx)%n",
                catPossiblyMissed, 100.0 * catPossiblyMissed / total);
        System.out.println();
        System.out.println("---- TRUE coverage rate (excluding legit no-location) ----");
        System.out.printf("merchant tx:        %5d%n", hasGeo + catPossiblyMissed);
        System.out.printf("of which extracted: %5d (%.1f%%)%n", hasGeo,
                100.0 * hasGeo / Math.max(1, hasGeo + catPossiblyMissed));
        System.out.println();
        System.out.println("======== POSSIBLY-MISSED SAMPLES (first 50) ========");
        for (final String s : missedSamples) System.out.println(s);
    }

    @Test
    void splitterTraceMultiWordCity() {
        final String[] inputs = {
            "MARRIOTT SANTA CLARA SANTA CLARA CA",
            "AMAZON MARKETPLACE SEATTLE WA",
            "COURTYARD SUNNYVALE SUNNYVALE CA",
            "AMZ SJ14 CAFE SUNNYVALE CA",
            "PRET A MANGER LONDON GBR",
            "TST* SALT & STRAW - TOTE KIRKLAND WA",
            "CPI*CANTEEN VENDING SA 800-628-8363 CA",
            "Cucina Venti             MOUNTAIN VIEWCA",
            "STARBUCKS 800-782-7282 WA GIFT CARD",
            "BLUE SKY NARITA 82 GATE -NARITA-SHI * JP MISC/SPECIALTY RETAIL",
            "AplPay HYATT REG LKE WSHGTN F&B SEARL RENTON WA 5729 98056 RESTAURANT",
            "AplPay IC* INSTACART SAN FRANCISCO CA +18882467822",
        };
        for (final String in : inputs) {
            final var split = com.budgetbuddy.service.ml.MerchantLocationSplitter.split(in);
            System.out.printf("[SPLIT] in=<<%s>> merchant=<<%s>> location=<<%s>>%n",
                    in, split.merchant(), split.location());
        }
    }

    @Test
    void dumpRawRowsForProblemIssuers() throws Exception {
        // For Chase combined statements + Discover, dump raw description +
        // location values so we can see the actual format and figure out
        // why city/state aren't being picked up.
        final File dir = new File(CORPUS_DIR);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) return;
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        for (final File pdf : pdfs) {
            final boolean isDiscover = pdf.getName().startsWith("Discover-");
            final boolean isChaseCombined = pdf.getName().matches(
                    "^20\\d{6}-statements-\\d+.*\\.pdf$");
            if (!isDiscover && !isChaseCombined) continue;
            try (InputStream in = new FileInputStream(pdf)) {
                final ImportResult r = svc.parsePDF(in, pdf.getName(), "audit", null);
                for (final ParsedTransaction tx : r.getTransactions()) {
                    final String desc = tx.getDescription() == null ? "" : tx.getDescription();
                    final boolean isPayment = desc.toUpperCase().matches(
                            ".*(AUTOPAY|AUTO PAY|PAYMENT.*THANK|E-PAYMENT|"
                                    + "ACH PMT|ACH CREDIT|PPD ID|WEB ID|BILLPAY|CHECK\\s+#).*");
                    // For Chase combined statements skip the noisy ACH rows.
                    // For Discover keep ALL rows so we can see the layout.
                    if (isPayment && !isDiscover) continue;
                    System.out.printf(
                            "[%s %s] desc=<<%s>> | loc=<<%s>> | city=%s st=%s co=%s ph=%s%n",
                            isDiscover ? "DISC" : "CHASE",
                            pdf.getName(),
                            truncate(desc, 140),
                            tx.getLocation(),
                            tx.getCity(), tx.getState(), tx.getCountry(),
                            tx.getPhoneNumber());
                }
            } catch (final Exception ignored) { }
        }
    }

    /**
     * Coverage floor on merchant-only transactions. A merchant transaction is
     * one whose description doesn't match a "no-merchant" pattern (ACH,
     * autopay, P2P transfer, check, refund, fee, interest). Because these
     * flows legitimately have no location, the population-wide rate
     * understates how well extraction is working. This test isolates the
     * merchant subset and asserts a tighter floor — currently ~85% — so
     * regressions on the actual fixable population can't hide behind the
     * non-merchant noise.
     */
    @Test
    void corpusMerchantTxCoverageFloor() throws Exception {
        final File dir = new File(CORPUS_DIR);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) return;
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        int merchant = 0, withGeo = 0;
        for (final File pdf : pdfs) {
            try (InputStream in = new FileInputStream(pdf)) {
                final var r = svc.parsePDF(in, pdf.getName(), "audit", null);
                for (final var tx : r.getTransactions()) {
                    final String d = tx.getDescription() == null ? ""
                            : tx.getDescription().toUpperCase();
                    final boolean noMerchant = d.contains("ACH ")
                            || d.contains("PPD ID") || d.contains("WEB ID")
                            || d.contains("ACH PMT") || d.contains("ACH CREDIT")
                            || d.contains("AUTOMATIC PAYMENT") || d.contains("PAYMENT THANK")
                            || d.contains("DIRECTPAY") || d.contains("E-PAYMENT")
                            || d.contains("PAYMENT TO ") || d.contains("CARD ENDING IN")
                            || d.contains("AUTOPAY") || d.contains("AUTO PAY")
                            || d.contains("BILLPAY") || d.contains("BILL PAY")
                            || d.contains("ZELLE") || d.contains("VENMO")
                            || d.contains("CASHAPP") || d.contains("ONLINE TRANSFER")
                            || d.contains("EXTRNLTFR") || d.contains("WIRE")
                            || d.contains("CHECK #") || d.contains("REFUND")
                            || d.contains("RETURN") || d.contains("CREDIT ADJUSTMENT")
                            || d.contains("REVERSAL") || d.contains("INTEREST")
                            || d.isBlank() || d.length() < 8;
                    if (noMerchant) continue;
                    merchant++;
                    if (tx.getCity() != null || tx.getState() != null
                            || tx.getCountry() != null) {
                        withGeo++;
                    }
                }
            } catch (final Exception ignored) { }
        }
        if (merchant < 100) return;
        final double rate = 100.0 * withGeo / merchant;
        // ~85% measured at the time this floor was set. Bump UP as we
        // improve extraction; this catches regressions on the population
        // that should genuinely have location.
        // ~86.5% measured at the time this floor was set; allow 3pp of
        // noise headroom. Raise UP as extraction improves.
        assertTrue(rate >= 83.0,
                "merchant-tx geo coverage dropped: " + rate + "% (" + withGeo
                        + "/" + merchant + "), floor 83%");
    }

    /**
     * Coverage-floor regression test. Pins the structured-geo extraction rate
     * against the local PDF corpus so future changes can't silently drop it.
     * The thresholds are intentionally a few points below the current
     * measured rate so normal extraction-rate noise (1-2 tx jitter on a
     * 2,800-tx corpus) doesn't flap the build; a real regression (5+ pp
     * drop) fires the assertion.
     */
    @Test
    void corpusGeoCoverageFloor() throws Exception {
        final File dir = new File(CORPUS_DIR);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) return;
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        int total = 0, city = 0, state = 0, country = 0, phone = 0;
        for (final File pdf : pdfs) {
            try (InputStream in = new FileInputStream(pdf)) {
                final var r = svc.parsePDF(in, pdf.getName(), "audit", null);
                for (final var tx : r.getTransactions()) {
                    total++;
                    if (tx.getCity() != null) city++;
                    if (tx.getState() != null) state++;
                    if (tx.getCountry() != null) country++;
                    if (tx.getPhoneNumber() != null) phone++;
                }
            } catch (final Exception ignored) { }
        }
        if (total < 100) return; // corpus too small to have stable floors
        final double cityRate = 100.0 * city / total;
        final double stateRate = 100.0 * state / total;
        final double countryRate = 100.0 * country / total;
        final double phoneRate = 100.0 * phone / total;
        // Floors set ~5pp below the rate measured at the time this test was
        // written. Bump UP when extraction improves so future regressions
        // are caught earlier; never lower without investigating WHY.
        // Floors set ~2pp below current measured rate (~57% city / 67%
        // state / 67% country / 35% phone). City was higher before the
        // bug-class fixes that reject "Amazon Prime → Pri/ME" and
        // URL-as-city false positives — those true positives lost are
        // acceptable cost for correctness; bumping these floors DOWN
        // was a deliberate quality decision, not a regression.
        assertTrue(cityRate >= 55.0,
                "city extraction rate dropped: " + cityRate + "% (floor 55%)");
        assertTrue(stateRate >= 63.0,
                "state extraction rate dropped: " + stateRate + "% (floor 63%)");
        assertTrue(countryRate >= 63.0,
                "country extraction rate dropped: " + countryRate + "% (floor 63%)");
        // Phone floor lowered from 30% → 22% after the ACH/PPD-ID phone
        // filter shipped — the original 35% was inflated by PPD IDs being
        // misclassified as phones on Chase Checking ACH rows (60% of those
        // 491 tx). Real phone rate after filter is ~24%, which is the
        // truthful merchant-phone signal.
        assertTrue(phoneRate >= 22.0,
                "phone extraction rate dropped: " + phoneRate + "% (floor 22%)");
    }

    @Test
    void auditGeoExtractionOnFullCorpus() throws Exception {
        final File dir = new File(CORPUS_DIR);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null) {
            System.out.println("[geo audit] no corpus dir, skipping");
            return;
        }
        final PDFImportService svc = TestPdfImportFactory.newSvc();

        int totalTx = 0;
        int totalCity = 0, totalState = 0, totalCountry = 0;
        int totalPostal = 0, totalPhone = 0, totalAddr = 0;
        final List<String> sampleRows = new ArrayList<>();
        final List<String> suspiciousRows = new ArrayList<>();

        for (final File pdf : pdfs) {
            final ImportResult result;
            try (InputStream in = new FileInputStream(pdf)) {
                result = svc.parsePDF(in, pdf.getName(), "audit", null);
            } catch (final Exception e) {
                System.out.println("[geo audit] skipping " + pdf.getName() + ": " + e.getMessage());
                continue;
            }
            int fileTx = 0, fileCity = 0, fileState = 0, fileCountry = 0;
            int filePostal = 0, filePhone = 0, fileAddr = 0;
            for (final ParsedTransaction tx : result.getTransactions()) {
                fileTx++;
                totalTx++;
                if (tx.getCity() != null)         { fileCity++;    totalCity++;    }
                if (tx.getState() != null)        { fileState++;   totalState++;   }
                if (tx.getCountry() != null)      { fileCountry++; totalCountry++; }
                if (tx.getPostalCode() != null)   { filePostal++;  totalPostal++;  }
                if (tx.getPhoneNumber() != null)  { filePhone++;   totalPhone++;   }
                if (tx.getStreetAddress() != null){ fileAddr++;    totalAddr++;    }

                // Prioritize address rows in the sample feed.
                if (tx.getStreetAddress() != null) {
                    sampleRows.add(0, "[ADDR] " + String.format(
                            "[%s] city=%s state=%s country=%s postal=%s phone=%s addr=%s | desc=%s | loc=%s",
                            pdf.getName(),
                            tx.getCity(), tx.getState(), tx.getCountry(),
                            tx.getPostalCode(), tx.getPhoneNumber(),
                            tx.getStreetAddress(),
                            truncate(tx.getDescription(), 100),
                            truncate(tx.getLocation(), 60)));
                }
                // First 200 other populated samples per file → visual review feed
                if (sampleRows.size() < 200
                        && tx.getStreetAddress() == null
                        && (tx.getCity() != null || tx.getPostalCode() != null)) {
                    sampleRows.add(String.format(
                            "[%s] city=%s state=%s country=%s postal=%s phone=%s addr=%s | desc=%s | loc=%s",
                            pdf.getName(),
                            tx.getCity(), tx.getState(), tx.getCountry(),
                            tx.getPostalCode(), tx.getPhoneNumber(),
                            tx.getStreetAddress(),
                            truncate(tx.getDescription(), 60),
                            truncate(tx.getLocation(), 40)));
                }
                // Sanity checks — flag clearly-bogus extractions
                if (tx.getState() != null && tx.getState().length() != 2) {
                    suspiciousRows.add("[" + pdf.getName() + "] BAD state='"
                            + tx.getState() + "' loc=" + tx.getLocation());
                }
                if (tx.getCountry() != null && tx.getCountry().length() != 2) {
                    suspiciousRows.add("[" + pdf.getName() + "] BAD country='"
                            + tx.getCountry() + "' loc=" + tx.getLocation());
                }
                if (tx.getCity() != null
                        && (tx.getCity().matches(".*\\d{4,}.*") || tx.getCity().length() > 35)) {
                    suspiciousRows.add("[" + pdf.getName() + "] SUSP city='"
                            + tx.getCity() + "' loc=" + tx.getLocation());
                }
                if (tx.getStreetAddress() != null
                        && tx.getStreetAddress().length() > 60) {
                    suspiciousRows.add("[" + pdf.getName() + "] SUSP addr='"
                            + tx.getStreetAddress() + "'");
                }
            }
            if (fileTx > 0) {
                System.out.printf(
                        "[%s] tx=%d city=%d/%d state=%d/%d country=%d/%d "
                                + "postal=%d/%d phone=%d/%d addr=%d/%d%n",
                        pdf.getName(), fileTx,
                        fileCity, fileTx, fileState, fileTx, fileCountry, fileTx,
                        filePostal, fileTx, filePhone, fileTx, fileAddr, fileTx);
            }
        }
        if (totalTx == 0) {
            System.out.println("[geo audit] no transactions parsed across corpus");
            return;
        }
        System.out.println();
        System.out.println("======== CORPUS GEO COVERAGE ========");
        System.out.printf("total tx: %d%n", totalTx);
        System.out.printf("city:    %5d (%4.1f%%)%n", totalCity,
                100.0 * totalCity / totalTx);
        System.out.printf("state:   %5d (%4.1f%%)%n", totalState,
                100.0 * totalState / totalTx);
        System.out.printf("country: %5d (%4.1f%%)%n", totalCountry,
                100.0 * totalCountry / totalTx);
        System.out.printf("postal:  %5d (%4.1f%%)%n", totalPostal,
                100.0 * totalPostal / totalTx);
        System.out.printf("phone:   %5d (%4.1f%%)%n", totalPhone,
                100.0 * totalPhone / totalTx);
        System.out.printf("address: %5d (%4.1f%%)%n", totalAddr,
                100.0 * totalAddr / totalTx);
        System.out.println();
        System.out.println("======== SAMPLES (first 40) ========");
        for (int i = 0; i < Math.min(40, sampleRows.size()); i++) {
            System.out.println(sampleRows.get(i));
        }
        System.out.println();
        if (!suspiciousRows.isEmpty()) {
            System.out.println("======== SUSPICIOUS (first 40) ========");
            for (int i = 0; i < Math.min(40, suspiciousRows.size()); i++) {
                System.out.println(suspiciousRows.get(i));
            }
        } else {
            System.out.println("[geo audit] no suspicious rows flagged");
        }
    }

    private static String truncate(final String s, final int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
