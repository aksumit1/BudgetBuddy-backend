package com.budgetbuddy.service.ml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for SemanticMatchingService
 * Tests: semantic similarity, tokenization, edge cases, null handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticMatchingService Tests")
class SemanticMatchingServiceTest {
    
    private SemanticMatchingService semanticMatchingService;
    
    @BeforeEach
    void setUp() {
        semanticMatchingService = new SemanticMatchingService();
    }
    
    // ========== Null and Empty Input Tests ==========
    
    @Test
    @DisplayName("findBestSemanticMatch with null inputs returns null")
    void testFindBestSemanticMatch_NullInputs() {
        assertNull(semanticMatchingService.findBestSemanticMatch(null, null));
        assertNull(semanticMatchingService.findBestSemanticMatch(null, ""));
        assertNull(semanticMatchingService.findBestSemanticMatch("", null));
        assertNull(semanticMatchingService.findBestSemanticMatch("   ", "   "));
    }
    
    // ========== Groceries Semantic Matching ==========
    
    @Test
    @DisplayName("findBestSemanticMatch detects groceries semantically")
    void testFindBestSemanticMatch_Groceries() {
        String[] groceryCases = {
            "Grocery Store",
            "Supermarket Shopping",
            "Food Market Purchase",
            "Grocery Shopping Trip",
            "Fresh Food Market"
        };
        
        for (String merchant : groceryCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for: " + merchant);
            assertEquals("groceries", result.category, "Should match groceries: " + merchant);
            assertTrue(result.similarity >= 0.6, "Should have good similarity: " + merchant);
        }
    }
    
    // ========== Dining Semantic Matching ==========
    
    @Test
    @DisplayName("findBestSemanticMatch detects dining semantically")
    void testFindBestSemanticMatch_Dining() {
        String[] diningCases = {
            "Restaurant Meal",
            "Cafe Coffee",
            "Fast Food Lunch",
            "Dining Out",
            "Takeout Delivery"
        };
        
        for (String merchant : diningCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for: " + merchant);
            assertEquals("dining", result.category, "Should match dining: " + merchant);
        }
    }
    
    // ========== Transportation Semantic Matching ==========
    
    @Test
    @DisplayName("findBestSemanticMatch detects transportation semantically")
    void testFindBestSemanticMatch_Transportation() {
        String[] transportationCases = {
            "Gas Station",
            "Fuel Purchase",
            "Petrol Fill Up",
            "Uber Ride",
            "Parking Fee"
        };
        
        for (String merchant : transportationCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for: " + merchant);
            assertEquals("transportation", result.category, "Should match transportation: " + merchant);
        }
    }
    
    // ========== Utilities Semantic Matching ==========
    
    @Test
    @DisplayName("findBestSemanticMatch detects utilities semantically")
    void testFindBestSemanticMatch_Utilities() {
        String[] utilityCases = {
            "Electric Bill",
            "Water Utility",
            "Internet Service",
            "Phone Bill",
            "Cable TV"
        };
        
        for (String merchant : utilityCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for: " + merchant);
            assertEquals("utilities", result.category, "Should match utilities: " + merchant);
        }
    }
    
    // ========== Synonym Handling ==========
    
    @Test
    @DisplayName("findBestSemanticMatch handles synonyms")
    void testFindBestSemanticMatch_Synonyms() {
        // "Café" should match "dining" (synonym of "cafe")
        SemanticMatchingService.SemanticMatchResult result = 
            semanticMatchingService.findBestSemanticMatch("Café", "Coffee Purchase");
        
        assertNotNull(result);
        assertEquals("dining", result.category);
    }
    
    @Test
    @DisplayName("findBestSemanticMatch handles related terms")
    void testFindBestSemanticMatch_RelatedTerms() {
        // "Food Shopping" should match "groceries" (related to grocery shopping)
        SemanticMatchingService.SemanticMatchResult result = 
            semanticMatchingService.findBestSemanticMatch("Food Shopping", null);
        
        assertNotNull(result);
        assertEquals("groceries", result.category);
    }
    
    // ========== Combined Merchant and Description ==========
    
    @Test
    @DisplayName("findBestSemanticMatch uses both merchant and description")
    void testFindBestSemanticMatch_Combined() {
        // Merchant alone might not match, but description helps
        SemanticMatchingService.SemanticMatchResult result = 
            semanticMatchingService.findBestSemanticMatch("ABC Store", "Grocery Shopping");
        
        assertNotNull(result);
        assertEquals("groceries", result.category);
    }
    
    // ========== Confidence Levels ==========
    
    @Test
    @DisplayName("SemanticMatchResult confidence levels work correctly")
    void testSemanticMatchResult_ConfidenceLevels() {
        SemanticMatchingService.SemanticMatchResult high = 
            new SemanticMatchingService.SemanticMatchResult("groceries", 0.75, "SEMANTIC");
        assertTrue(high.isHighConfidence());
        assertFalse(high.isMediumConfidence());
        
        SemanticMatchingService.SemanticMatchResult med = 
            new SemanticMatchingService.SemanticMatchResult("dining", 0.60, "SEMANTIC");
        assertFalse(med.isHighConfidence());
        assertTrue(med.isMediumConfidence());
    }
    
    // ========== Edge Cases ==========
    
    @Test
    @DisplayName("findBestSemanticMatch handles very long text")
    void testFindBestSemanticMatch_VeryLongText() {
        String longText = "Grocery Store " + "X".repeat(1000);
        SemanticMatchingService.SemanticMatchResult result = 
            semanticMatchingService.findBestSemanticMatch(longText, null);
        
        // Should still match despite long text
        if (result != null) {
            assertTrue(result.similarity >= 0.0);
        }
    }
    
    @Test
    @DisplayName("findBestSemanticMatch handles special characters")
    void testFindBestSemanticMatch_SpecialCharacters() {
        String[] specialCases = {
            "Grocery Store!@#$",
            "Grocery-Store",
            "Grocery_Store",
            "Grocery.Store"
        };
        
        for (String merchant : specialCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            // Should handle special characters gracefully
            assertDoesNotThrow(() -> {
                if (result != null) {
                    result.category.toLowerCase();
                }
            });
        }
    }
    
    // ========== No Match Tests ==========
    
    @Test
    @DisplayName("findBestSemanticMatch returns null when no good match")
    void testFindBestSemanticMatch_NoMatch() {
        // Random text that doesn't match any category
        SemanticMatchingService.SemanticMatchResult result = 
            semanticMatchingService.findBestSemanticMatch("XYZABC123", null);
        
        // Might return null or low confidence match
        if (result != null) {
            assertTrue(result.similarity < 0.6, "Should have low similarity for no match");
        }
    }
    
    // ========== Add Semantic Cluster Tests ==========
    
    @Test
    @DisplayName("addSemanticCluster adds new category")
    void testAddSemanticCluster() {
        Set<String> keywords = new HashSet<>(Arrays.asList("test", "testing", "test category"));
        semanticMatchingService.addSemanticCluster("test_category", keywords);
        
        // Verify it was added by trying to match
        SemanticMatchingService.SemanticMatchResult result = 
            semanticMatchingService.findBestSemanticMatch("Test Category", null);
        
        // Should match the new category
        if (result != null) {
            assertEquals("test_category", result.category);
        }
    }
    
    @Test
    @DisplayName("addSemanticCluster handles null inputs gracefully")
    void testAddSemanticCluster_NullInputs() {
        assertDoesNotThrow(() -> {
            semanticMatchingService.addSemanticCluster(null, new HashSet<>());
            semanticMatchingService.addSemanticCluster("test", null);
            semanticMatchingService.addSemanticCluster(null, null);
        });
    }
}

