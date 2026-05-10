package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MccDirectoryTest {

    @Test
    void groceryKeywordsMapToGroceries() {
        assertEquals(
                Optional.of("groceries"),
                MccDirectory.categoryForMerchant("WHOLE FOODS MKT #12345"));
        assertEquals(
                Optional.of("groceries"), MccDirectory.categoryForMerchant("Trader Joe's #201"));
        assertEquals(
                Optional.of("groceries"),
                MccDirectory.categoryForMerchant("SAFEWAY 1234 SAN JOSE"));
    }

    @Test
    void longestKeywordWinsOverShorterMatch() {
        // "amazon fresh" is a grocery keyword; "amazon.com" is a shopping keyword;
        // both substrings appear in "AMAZON FRESH 123 SEATTLE".
        assertEquals(
                Optional.of("groceries"),
                MccDirectory.categoryForMerchant("AMAZON FRESH 123 SEATTLE"));
    }

    @Test
    void bareAmazonMapsToShopping() {
        assertEquals(
                Optional.of("shopping"),
                MccDirectory.categoryForMerchant("AMAZON.COM AMZN.COM/BILL WA"));
    }

    @Test
    void streamingSubscriptionsMapToSubscriptions() {
        assertEquals(Optional.of("subscriptions"), MccDirectory.categoryForMerchant("NETFLIX.COM"));
        assertEquals(
                Optional.of("subscriptions"), MccDirectory.categoryForMerchant("Spotify Premium"));
    }

    @Test
    void fuelMapsToTransportation() {
        assertEquals(
                Optional.of("transportation"),
                MccDirectory.categoryForMerchant("CHEVRON 12345 SEATTLE"));
        assertEquals(
                Optional.of("transportation"),
                MccDirectory.categoryForMerchant("Shell Oil 57534451212"));
    }

    @Test
    void atmMapsToCash() {
        assertEquals(Optional.of("cash"), MccDirectory.categoryForMcc("6011"));
    }

    @Test
    void unknownMerchantReturnsEmpty() {
        assertEquals(
                Optional.empty(), MccDirectory.categoryForMerchant("QUIRKY LOCAL BUSINESS 123"));
    }

    @Test
    void nullSafe() {
        assertEquals(Optional.empty(), MccDirectory.mccForMerchant(null));
        assertEquals(Optional.empty(), MccDirectory.categoryForMcc(null));
        assertEquals(Optional.empty(), MccDirectory.categoryForMerchant(null));
    }
}
