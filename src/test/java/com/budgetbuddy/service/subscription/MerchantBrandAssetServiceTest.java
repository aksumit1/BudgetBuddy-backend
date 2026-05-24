package com.budgetbuddy.service.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.subscription.MerchantBrandAssetService.BrandAsset;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class MerchantBrandAssetServiceTest {

    private MerchantBrandAssetService svc;

    @BeforeEach
    void setUp() {
        svc = new MerchantBrandAssetService();
        svc.load();
    }

    @Test
    void resolvesExactBrandMatch() {
        final Optional<BrandAsset> b = svc.lookup("Netflix");
        assertTrue(b.isPresent());
        assertEquals("netflix.com", b.get().domain);
        assertEquals("Netflix", b.get().displayName);
    }

    @Test
    void prefersLongerKeyWhenMultipleMatch() {
        // "apple tv" matches "apple tv+" (longer) before bare "apple"
        // because of length-desc ordering — protects against
        // alias-shadowing.
        final BrandAsset b = svc.lookupOrNull("apple tv+ subscription");
        assertEquals("Apple TV+", b.displayName);
    }

    @Test
    void caseAndNoiseInsensitive() {
        final BrandAsset b = svc.lookupOrNull("NETFLIX*MONTHLY*US");
        assertEquals("netflix.com", b.domain);
    }

    @Test
    void unknownMerchantReturnsEmpty() {
        assertTrue(svc.lookup("Random Local Cafe").isEmpty());
    }

    @Test
    void nullOrBlankReturnsEmpty() {
        assertTrue(svc.lookup(null).isEmpty());
        assertTrue(svc.lookup("").isEmpty());
        assertTrue(svc.lookup("   ").isEmpty());
    }
}
