package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.util.Map;
import java.util.Locale;

/**
 * Map OpenStreetMap tags to internal {@link CategoryResult}. OSM uses a
 * structured tag namespace ({@code shop=*}, {@code amenity=*},
 * {@code tourism=*}, {@code office=*}, etc.) that's effectively a
 * crowd-sourced merchant taxonomy. This class is the bridge.
 *
 * <p>Reference: <a href="https://wiki.openstreetmap.org/wiki/Map_features">
 * OSM Map Features</a> — every key/value pair documented there.
 *
 * <p>Confidence is set at 0.88 by default — high enough to win against
 * fuzzy/ML guesses but lower than {@link MCCCodeMapper} (issuer-set MCC
 * is more authoritative than crowd-edited OSM tags).
 */
public final class OsmTagMapper {

    private static final double OSM_CONFIDENCE = 0.88;

    private OsmTagMapper() {
        // static helpers only
    }

    /**
     * Resolve OSM tags to a {@link CategoryResult} or null if none of the
     * tags map to a known category.
     *
     * @param tags raw OSM key/value tags from Overpass / Nominatim
     */
    public static CategoryResult fromTags(final Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        // Order matters — check most specific keys first.
        final String[] checkOrder = {
                "amenity", "shop", "tourism", "leisure", "office", "healthcare", "craft", "sport"
        };
        for (final String key : checkOrder) {
            final String value = tags.get(key);
            if (value == null) {
                continue;
            }
            final String cat = lookup(key, value.toLowerCase(Locale.ROOT));
            if (cat != null) {
                return new CategoryResult(cat, cat, "OSM_TAG:" + key + "=" + value, OSM_CONFIDENCE);
            }
        }
        return null;
    }

    private static String lookup(final String key, final String value) {
        switch (key) {
            case "amenity":     return fromAmenity(value);
            case "shop":        return fromShop(value);
            case "tourism":     return fromTourism(value);
            case "leisure":     return fromLeisure(value);
            case "office":      return fromOffice(value);
            case "healthcare":  return "health";
            case "craft":       return fromCraft(value);
            case "sport":       return "entertainment";
            default:            return null;
        }
    }

    private static String fromAmenity(final String value) {
        switch (value) {
            case "restaurant":
            case "fast_food":
            case "cafe":
            case "food_court":
            case "ice_cream":
            case "pub":
            case "bar":
            case "biergarten":
                return "dining";
            case "atm":
            case "bank":
            case "bureau_de_change":
                return "fees";
            case "cinema":
            case "theatre":
            case "nightclub":
            case "casino":
            case "arts_centre":
            case "events_venue":
                return "entertainment";
            case "fuel":
            case "charging_station":
            case "parking":
            case "parking_entrance":
            case "car_wash":
            case "car_rental":
            case "car_sharing":
            case "taxi":
            case "bus_station":
            case "ferry_terminal":
                return "transportation";
            case "pharmacy":
            case "hospital":
            case "clinic":
            case "doctors":
            case "dentist":
            case "veterinary":
            case "spa":
                return value.equals("veterinary") ? "pet" : "health";
            case "school":
            case "university":
            case "college":
            case "kindergarten":
            case "library":
            case "language_school":
            case "music_school":
            case "training":
                return "education";
            case "place_of_worship":
            case "community_centre":
            case "social_facility":
                return "charity";
            case "post_office":
            case "post_box":
                return "transportation"; // shipping rolls into transport in our taxonomy
            default:
                return null;
        }
    }

    private static String fromShop(final String value) {
        switch (value) {
            // Food / groceries
            case "supermarket":
            case "convenience":
            case "greengrocer":
            case "butcher":
            case "bakery":
            case "deli":
            case "seafood":
            case "cheese":
            case "wine":
            case "alcohol":
            case "beverages":
            case "tea":
            case "coffee":
            case "spices":
            case "farm":
            case "pasta":
            case "dairy":
                return "groceries";
            // Apparel / shopping
            case "clothes":
            case "shoes":
            case "fashion":
            case "boutique":
            case "jewelry":
            case "watches":
            case "bag":
            case "fabric":
            case "leather":
            case "department_store":
            case "general":
            case "variety_store":
            case "mall":
            case "outdoor":
            case "sports":
            case "ticket":
            case "art":
            case "antiques":
            case "second_hand":
            case "books":
            case "stationery":
            case "music":
            case "musical_instrument":
            case "toys":
            case "video_games":
            case "gift":
            case "florist":
            case "perfumery":
            case "cosmetics":
            case "tobacco":
            case "newsagent":
            case "kiosk":
                return "shopping";
            // Electronics / tech
            case "electronics":
            case "computer":
            case "mobile_phone":
            case "hifi":
            case "camera":
            case "video":
                return "tech";
            // Home improvement
            case "doityourself":
            case "hardware":
            case "paint":
            case "tile":
            case "trade":
            case "garden_centre":
            case "florist_supplies":
            case "houseware":
            case "furniture":
            case "kitchen":
            case "bathroom_furnishing":
            case "carpet":
            case "interior_decoration":
                return "home improvement";
            // Pet
            case "pet":
            case "pet_grooming":
                return "pet";
            // Beauty / health
            case "hairdresser":
            case "beauty":
            case "tattoo":
            case "massage":
            case "nutrition_supplements":
            case "optician":
            case "hearing_aids":
            case "medical_supply":
            case "chemist":
                return "health";
            // Vehicles
            case "car":
            case "car_parts":
            case "car_repair":
            case "motorcycle":
            case "motorcycle_repair":
            case "bicycle":
            case "tyres":
            case "fuel":
                return "transportation";
            default:
                return null;
        }
    }

    private static String fromTourism(final String value) {
        switch (value) {
            case "hotel":
            case "motel":
            case "guest_house":
            case "hostel":
            case "apartment":
            case "chalet":
            case "camp_site":
            case "caravan_site":
            case "alpine_hut":
            case "wilderness_hut":
            case "resort":
                return "travel";
            case "attraction":
            case "museum":
            case "gallery":
            case "theme_park":
            case "zoo":
            case "aquarium":
                return "entertainment";
            default:
                return null;
        }
    }

    private static String fromLeisure(final String value) {
        switch (value) {
            case "fitness_centre":
            case "sports_centre":
            case "pitch":
            case "swimming_pool":
            case "stadium":
            case "track":
            case "golf_course":
            case "sauna":
                return "health";
            case "park":
            case "garden":
            case "nature_reserve":
            case "amusement_arcade":
            case "bowling_alley":
            case "escape_game":
                return "entertainment";
            default:
                return null;
        }
    }

    private static String fromOffice(final String value) {
        switch (value) {
            case "insurance":
                return "insurance";
            case "lawyer":
            case "accountant":
            case "tax_advisor":
            case "financial":
            case "financial_advisor":
            case "estate_agent":
                return "fees";
            case "ngo":
            case "charity":
                return "charity";
            default:
                return null;
        }
    }

    private static String fromCraft(final String value) {
        // OSM craft=* covers tradespeople (plumber, electrician, etc.) —
        // most are home-improvement related on a credit card statement.
        return "home improvement";
    }
}
