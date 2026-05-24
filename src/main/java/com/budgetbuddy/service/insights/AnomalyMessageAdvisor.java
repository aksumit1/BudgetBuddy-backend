package com.budgetbuddy.service.insights;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * AI-3: optional LLM hook that turns a static anomaly reason string into
 * a personalised one-liner.
 *
 * <p>The deterministic detector produces messages like
 * "Transaction amount ($87.42) is 3.1 standard deviations from your
 * average". Functional, but jargon-y. An LLM can rewrite it as
 * "$87 at Whole Foods is well above your typical $25 grocery run —
 * was this an unusually large trip?".
 *
 * <p>Contract: the advisor MUST NOT change the underlying severity, anomaly
 * type, or amount. It only annotates a {@code humanMessage} field on each
 * anomaly. On any failure (LLM down, blank API key, parse error) the
 * implementation returns the input list unchanged so the deterministic
 * path always remains authoritative.
 */
public interface AnomalyMessageAdvisor {

    /** Annotate each input alert in place with a personalised message. */
    List<AnomalyContext> annotate(List<AnomalyContext> alerts);

    /**
     * Minimal context the advisor needs — pulled out so the
     * detector doesn't have to expose its own internal types.
     */
    final class AnomalyContext {
        public String anomalyId;
        public String type; // "AMOUNT_SPIKE" | "CATEGORY_SPIKE" | "FREQUENCY_CHANGE" | …
        public String severity;
        public String category;
        public String merchantName;
        public BigDecimal amount;
        public BigDecimal historicalAverage;
        public LocalDate transactionDate;
        public String deterministicReason;
        /** Output — populated by the advisor; null otherwise. */
        public String humanMessage;
    }
}
