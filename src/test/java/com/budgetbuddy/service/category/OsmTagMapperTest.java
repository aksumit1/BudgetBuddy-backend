package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the OSM tag → internal-category mapping. The recent expansion
 * added {@code landuse} and {@code building} fallbacks so Nominatim
 * results that return a landuse parcel (Whole Foods Market → landuse=retail)
 * still map to a category. These tests pin that behaviour so a refactor
 * doesn't accidentally drop the fallbacks.
 */
class OsmTagMapperTest {

    @Test
    @DisplayName("amenity=cafe → dining (specific POI wins)")
    void amenityCafe() {
        CategoryResult r = OsmTagMapper.fromTags(Map.of("amenity", "cafe"));
        assertNotNull(r);
        assertEquals("dining", r.getCategoryPrimary());
        assertEquals("OSM_TAG:amenity=cafe", r.getSource());
    }

    @Test
    @DisplayName("shop=supermarket → groceries")
    void shopSupermarket() {
        CategoryResult r = OsmTagMapper.fromTags(Map.of("shop", "supermarket"));
        assertNotNull(r);
        assertEquals("groceries", r.getCategoryPrimary());
    }

    @Test
    @DisplayName("landuse=retail → shopping (NEW fallback)")
    void landuseRetailMapsToShopping() {
        CategoryResult r = OsmTagMapper.fromTags(Map.of("landuse", "retail"));
        assertNotNull(r, "landuse=retail must map (regression: was returning null pre-expansion)");
        assertEquals("shopping", r.getCategoryPrimary());
        assertEquals("OSM_TAG:landuse=retail", r.getSource());
    }

    @Test
    @DisplayName("landuse=commercial → shopping (NEW fallback)")
    void landuseCommercialMapsToShopping() {
        CategoryResult r = OsmTagMapper.fromTags(Map.of("landuse", "commercial"));
        assertNotNull(r);
        assertEquals("shopping", r.getCategoryPrimary());
    }

    @Test
    @DisplayName("building=hotel → travel (NEW fallback)")
    void buildingHotelMapsToTravel() {
        CategoryResult r = OsmTagMapper.fromTags(Map.of("building", "hotel"));
        assertNotNull(r);
        assertEquals("travel", r.getCategoryPrimary());
    }

    @Test
    @DisplayName("building=supermarket → groceries (NEW fallback)")
    void buildingSupermarketMapsToGroceries() {
        CategoryResult r = OsmTagMapper.fromTags(Map.of("building", "supermarket"));
        assertNotNull(r);
        assertEquals("groceries", r.getCategoryPrimary());
    }

    @Test
    @DisplayName("Specific POI wins over generic landuse — amenity over landuse")
    void specificPoiBeatsLanduse() {
        // If a row has BOTH amenity=restaurant AND landuse=retail, amenity should win
        // (the POI tag is more specific than the land-use signal).
        CategoryResult r = OsmTagMapper.fromTags(Map.of(
                "amenity", "restaurant",
                "landuse", "retail"));
        assertEquals("dining", r.getCategoryPrimary(),
                "amenity (specific POI) must beat landuse (generic land use)");
    }

    @Test
    @DisplayName("Unknown tags → null (no fallback hallucinations)")
    void unknownTagsReturnNull() {
        assertNull(OsmTagMapper.fromTags(Map.of("foo", "bar")));
        assertNull(OsmTagMapper.fromTags(Map.of("amenity", "totally_unknown_value")));
    }

    @Test
    @DisplayName("Empty / null tags → null")
    void emptyTagsReturnNull() {
        assertNull(OsmTagMapper.fromTags(Map.of()));
        assertNull(OsmTagMapper.fromTags(null));
    }
}
