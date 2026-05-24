package com.budgetbuddy.service.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.service.subscription.TaxDeductibilityClassifier.Deductibility;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class TaxDeductibilityClassifierTest {

    private final TaxDeductibilityClassifier classifier = new TaxDeductibilityClassifier();

    @Test
    void adobeIsFullyDeductible() {
        assertEquals(Deductibility.FULL, classifier.classify(sub("Adobe Creative Cloud")));
    }

    @Test
    void netflixIsNotDeductible() {
        assertEquals(Deductibility.NONE, classifier.classify(sub("Netflix")));
    }

    @Test
    void cloudStorageIsPartiallyDeductible() {
        assertEquals(Deductibility.PARTIAL, classifier.classify(sub("Dropbox")));
        assertEquals(Deductibility.PARTIAL, classifier.classify(sub("Google Drive 200GB")));
    }

    @Test
    void unknownMerchantReturnsUnknown() {
        assertEquals(Deductibility.UNKNOWN, classifier.classify(sub("Random Local Shop")));
    }

    @Test
    void caseInsensitiveMatching() {
        assertEquals(Deductibility.FULL, classifier.classify(sub("ADOBE PHOTOSHOP")));
        assertEquals(Deductibility.NONE, classifier.classify(sub("HULU+LIVE TV")));
    }

    @Test
    void nullSubscriptionReturnsUnknown() {
        assertEquals(Deductibility.UNKNOWN, classifier.classify(null));
        assertEquals(Deductibility.UNKNOWN, classifier.classify(new Subscription()));
    }

    private static Subscription sub(final String merchant) {
        final Subscription s = new Subscription();
        s.setMerchantName(merchant);
        return s;
    }
}
