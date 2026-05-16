package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Locks in global merchant→category coverage of the rules engine across
 * US + UK + EU + India + East Asia + LatAm. Each parameterised case is
 * a real-world merchant string as it appears on a credit-card statement;
 * regressions show up as test failures the moment a rule is removed or
 * a priority shifts in a way that changes dispatch.
 *
 * <p>Add a new merchant: add a row here AND a rule in
 * {@code category-rules-v2.yaml}. The two move together — if a rule
 * goes in without a test the next refactor can quietly drop it; if a
 * test goes in without a rule the test fails loudly.
 */
class MerchantCategoryGlobalCoverageTest {

    private final MerchantCategoryRules engine =
            new MerchantCategoryRules("category-rules-v2.yaml");

    private void expect(final String description, final String expectedCategory) {
        final String normalized = description.toLowerCase().trim();
        final String result = engine.match(normalized, normalized);
        assertEquals(
                expectedCategory,
                result,
                "Expected '" + description + "' → " + expectedCategory + " but got " + result);
    }

    // =====================================================================
    // DINING — global chains
    // =====================================================================
    @Nested
    @DisplayName("Dining — US chains")
    class DiningUS {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "'STARBUCKS STORE #4936 NEWCASTLE WA','dining'",
            "'SBUX RESERVE BELLEVUE WA','dining'",
            "'CHIPOTLE MEX GR ONLINE','dining'",
            "'MCDONALDS #1234','dining'",
            "'WENDYS #4321','dining'",
            "'BURGER KING #999','dining'",
            "'TACO BELL #1234','dining'",
            "'KFC #4567','dining'",
            "'POPEYES #1234','dining'",
            "'SUBWAY 12345','dining'",
            "'CHICK-FIL-A #543','dining'",
            "'CHICKFILA 1234','dining'",
            "'IN-N-OUT BURGER','dining'",
            "'IN N OUT BURGER','dining'",
            "'FIVE GUYS 1234','dining'",
            "'SHAKE SHACK BELLEVUE','dining'",
            "'PANERA BREAD #5','dining'",
            "'PIZZA HUT #888','dining'",
            "'PAPA JOHNS PIZZA','dining'",
            "'DOMINOS PIZZA','dining'",
            "'DUNKIN #1234','dining'",
            "'KRISPY KREME','dining'",
            "'TIM HORTONS','dining'",
            "'PANDA EXPRESS','dining'",
            "'OLIVE GARDEN','dining'",
            "'APPLEBEES #234','dining'",
            "'TEXAS ROADHOUSE','dining'",
            "'OUTBACK STEAKHOUSE','dining'",
            "'CHEESECAKE FACTORY','dining'",
            "'BUFFALO WILD WINGS','dining'",
            "'CRACKER BARREL','dining'",
            "'IHOP #5678','dining'",
            "'WAFFLE HOUSE','dining'",
            "'DENNYS #1234','dining'",
            "'GOLDEN CORRAL','dining'",
            "'RED LOBSTER','dining'",
            "'RED ROBIN','dining'",
            "'CHILIS GRILL','dining'",
            "'WINGSTOP 1234','dining'",
            "'ARBYS #234','dining'",
            "'JIMMY JOHNS','dining'",
            "'JERSEY MIKES SUBS','dining'",
            "'FIREHOUSE SUBS','dining'",
            "'SWEETGREEN','dining'",
            "'CAVA GRILL','dining'",
            "'QDOBA MEXICAN','dining'",
            "'JACK IN THE BOX','dining'",
            "'WHITE CASTLE','dining'",
            "'RAISING CANES','dining'",
            "'BOSTON MARKET','dining'",
            "'EL POLLO LOCO','dining'",
            "'BOJANGLES','dining'",
            "'BJS BREWHOUSE','dining'",
            "'PF CHANG BISTRO','dining'",
            "'FOGO DE CHAO','dining'",
            "'BENIHANA','dining'",
            "'RUTHS CHRIS','dining'",
            "'MORTONS THE STEAKHOUSE','dining'",
            "'CAPITAL GRILLE','dining'",
            "'CINNABON','dining'",
            "'BASKIN ROBBINS','dining'",
            "'DAIRY QUEEN','dining'",
            "'COLD STONE','dining'",
            "'BEN AND JERRY','dining'",
            "'HAAGEN-DAZS','dining'",
            "'AUNTIE ANNES','dining'",
            "'PEETS COFFEE','dining'",
            "'DUTCH BROS COFFEE','dining'",
            "'BLUE BOTTLE COFFEE','dining'",
            "'PHILZ COFFEE','dining'"
        })
        void usDiningChains(String desc, String cat) {
            expect(desc, cat);
        }
    }

    @Nested
    @DisplayName("Dining — UK chains")
    class DiningUK {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "'NANDOS WESTFIELD','dining'",
            "'PRET A MANGER LONDON','dining'",
            "'WAGAMAMA SOHO','dining'",
            "'COSTA COFFEE LDN','dining'",
            "'CAFFE NERO HIGH ST','dining'",
            "'GREGGS BAKERY','dining'",
            "'PIZZA EXPRESS','dining'",
            "'ITSU SUSHI','dining'",
            "'LEON RESTAURANT','dining'",
            "'WAHACA CAMDEN','dining'",
            "'YO! SUSHI EUSTON','dining'",
            "'HONEST BURGERS','dining'",
            "'BILLS RESTAURANT','dining'"
        })
        void ukDiningChains(String desc, String cat) {
            expect(desc, cat);
        }
    }

    @Nested
    @DisplayName("Dining — Asian / Indian chains")
    class DiningAsia {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "'DIN TAI FUNG BELLEVUE','dining'",
            "'ICHIRAN RAMEN','dining'",
            "'IPPUDO NY','dining'",
            "'SUKIYA TOKYO','dining'",
            "'YOSHINOYA','dining'",
            "'GENKI SUSHI','dining'",
            "'KURA SUSHI','dining'",
            "'BONCHON CHICKEN','dining'",
            "'BBQ CHICKEN','dining'",
            "'HALDIRAMS DELHI','dining'",
            "'BARBEQUE NATION BLR','dining'",
            "'MAINLAND CHINA','dining'",
            "'SARAVANA BHAVAN','dining'",
            "'BIKANERVALA','dining'",
            "'INDIAN CUISINE BELLEVUE','dining'",
            "'INDIAN RESTAURANT BELLEVUE','dining'",
            "'MOTHER INDIA CUISINE','dining'",
            "'TIKKA MASALA HOUSE','dining'",
            "'BIRYANI HOUSE','dining'"
        })
        void asianDiningChains(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // GROCERIES — global
    // =====================================================================
    @Nested
    @DisplayName("Groceries — global")
    class Groceries {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            // US
            "'SAFEWAY #1444 BELLEVUE','groceries'",
            "'QFC #5822 BELLEVUE','groceries'",
            "'KROGER #1234','groceries'",
            "'PUBLIX #999','groceries'",
            "'WEGMANS #5','groceries'",
            "'ALBERTSONS #456','groceries'",
            "'VONS MARKET #1','groceries'",
            "'TRADER JOES BELLEVUE','groceries'",
            "'WHOLE FOODS MARKET','groceries'",
            "'FRED MEYER #555','groceries'",
            "'COSTCO WHSE #0006','groceries'",
            "'H-E-B AUSTIN TX','groceries'",
            "'HARRIS TEETER NC','groceries'",
            "'FOOD LION #99','groceries'",
            "'STOP AND SHOP','groceries'",
            "'ALDI #234','groceries'",
            "'LIDL US #1','groceries'",
            // UK
            "'TESCO METRO LDN','groceries'",
            "'SAINSBURYS CAMDEN','groceries'",
            "'WAITROSE STRAND','groceries'",
            "'M&S FOOD ST PANCRAS','groceries'",
            "'ASDA SUPERSTORE','groceries'",
            "'MORRISONS SUPERMARKET','groceries'",
            "'ICELAND FOODS','groceries'",
            "'CO-OP FOOD LONDON','groceries'",
            "'OCADO RETAIL','groceries'",
            // Europe
            "'CARREFOUR MARKET','groceries'",
            "'AUCHAN STORE','groceries'",
            "'REWE MARKT','groceries'",
            "'EDEKA MARKT','groceries'",
            // South Asian
            "'INDIA SUPERMARKET BELLEVUE','groceries'",
            "'PATEL BROTHERS','groceries'",
            "'APNA BAZAR BELLEVUE','groceries'",
            "'INDIA METRO HYPER','groceries'",
            "'NAMASTE INDIA','groceries'",
            "'SUBZI MANDI','groceries'",
            "'MAYURI FOODS','groceries'",
            "'HOUSE OF SPICES','groceries'",
            "'INDIAN BAZAAR','groceries'",
            // East Asian
            "'UWAJIMAYA','groceries'",
            "'H MART','groceries'",
            "'99 RANCH MARKET','groceries'",
            "'LAWSON TOKYO','groceries'",
            "'FAMILY MART','groceries'",
            "'DON QUIJOTE','groceries'",
            // Convenience
            "'7-ELEVEN #1234','groceries'",
            "'CIRCLE K #5','groceries'",
            "'WALMART NEIGHBORHOOD MARKET','groceries'",
            "'INSTACART INC','groceries'",
            "'AMAZON FRESH','groceries'"
        })
        void globalGroceries(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // TRAVEL — hotels + airlines + travel-services
    // =====================================================================
    @Nested
    @DisplayName("Travel — hotels global")
    class TravelHotels {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "'MARRIOTT BONVOY BETHESDA','travel'",
            "'HYATT REGENCY DENVER','travel'",
            "'COURTYARD MARRIOTT','travel'",
            "'HAMPTON INN SEATTLE','travel'",
            "'HILTON HONORS MCLEAN','travel'",
            "'DOUBLETREE SUITES','travel'",
            "'WESTIN BELLEVUE','travel'",
            "'SHERATON SEATTLE','travel'",
            "'RITZ-CARLTON SF','travel'",
            "'WALDORF ASTORIA','travel'",
            "'EMBASSY SUITES','travel'",
            "'FAIRFIELD INN','travel'",
            "'CROWNE PLAZA','travel'",
            "'HOLIDAY INN EXPRESS','travel'",
            "'BEST WESTERN PLUS','travel'",
            "'AIRBNB INC','travel'",
            "'VRBO LISTING','travel'",
            "'BOOKING.COM AMSTERDAM','travel'",
            "'EXPEDIA TRAVEL FEE','travel'",
            "'KAYAK.COM BOOKING','travel'",
            "'AGODA HOTELS','travel'",
            "'TRIP.COM RESERVATION','travel'",
            "'ALOFT SAN JOSE','travel'",
            "'MOUNTAIN VIEW ALOFT','travel'",
            "'JW MARRIOTT NY','travel'",
            "'CHOICE HOTELS','travel'",
            "'COMFORT INN','travel'",
            "'QUALITY INN','travel'",
            "'LA QUINTA INN','travel'",
            "'WYNDHAM HOTEL','travel'",
            "'DAYS INN','travel'",
            "'EXTENDED STAY','travel'",
            "'FOUR SEASONS','travel'",
            "'MANDARIN ORIENTAL','travel'",
            "'PENINSULA HOTEL','travel'",
            "'PREMIER INN UK','travel'",
            "'TRAVELODGE UK','travel'",
            "'IBIS HOTEL','travel'",
            "'NOVOTEL','travel'",
            "'MERCURE HOTEL','travel'",
            "'ITC HOTEL DELHI','travel'",
            "'OBEROI MUMBAI','travel'",
            "'OYO ROOMS BLR','travel'",
            "'VIVANTA TAJ','travel'",
            "'TAJ HOTEL DELHI','travel'",
            "'LEELA PALACE','travel'",
            "'GINGER HOTEL','travel'",
            "'LEMON TREE HOTEL','travel'",
            "'TSA PRECHECK','travel'",
            "'GLOBAL ENTRY DTOPS','travel'",
            "'CLEAR PLUS DENVER','travel'",
            "'PANASONIC AVIONICS','travel'",
            "'GOGO INFLIGHT','travel'",
            "'WI-FI ONBOARD DAL','travel'"
        })
        void hotels(String desc, String cat) {
            expect(desc, cat);
        }
    }

    @Nested
    @DisplayName("Travel — airlines global")
    class TravelAirlines {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "'DELTA AIRLINES ATL','travel'",
            "'UNITED AIRLINES CHI','travel'",
            "'AMERICAN AIRLINES','travel'",
            "'SOUTHWEST AIRLINES','travel'",
            "'JETBLUE AIRWAYS','travel'",
            "'ALASKA AIRLINES','travel'",
            "'SPIRIT AIRLINES','travel'",
            "'FRONTIER AIRLINES','travel'",
            "'HAWAIIAN AIRLINES','travel'",
            "'AIR CANADA','travel'",
            "'WESTJET FLIGHT','travel'",
            "'BRITISH AIRWAYS','travel'",
            "'LUFTHANSA FRANKFURT','travel'",
            "'AIR FRANCE PARIS','travel'",
            "'KLM AMSTERDAM','travel'",
            "'IBERIA MADRID','travel'",
            "'SWISS INTL ZRH','travel'",
            "'EASYJET LDN','travel'",
            "'RYANAIR DUBLIN','travel'",
            "'WIZZ AIR BUDAPEST','travel'",
            "'VIRGIN ATLANTIC LHR','travel'",
            "'EMIRATES DUBAI','travel'",
            "'ETIHAD AIRWAYS','travel'",
            "'QATAR AIRWAYS','travel'",
            "'CATHAY PACIFIC','travel'",
            "'SINGAPORE AIRLINES','travel'",
            "'ANA ALL NIPPON','travel'",
            "'JAPAN AIRLINES','travel'",
            "'KOREAN AIR SEOUL','travel'",
            "'ASIANA AIRLINES','travel'",
            "'EVA AIR TAIPEI','travel'",
            "'AIR INDIA DELHI','travel'",
            "'INDIGO AIRLINE','travel'",
            "'SPICEJET BLR','travel'",
            "'VISTARA DEL','travel'",
            "'QANTAS SYDNEY','travel'",
            "'BCD TRAVEL ATLANTA','travel'"
        })
        void airlines(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // TRANSPORTATION
    // =====================================================================
    @Nested
    @DisplayName("Transportation — gas + rideshare + transit (global)")
    class Transport {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            // US gas
            "'CHEVRON 0095482 SJC','transportation'",
            "'SHELL OIL 57444','transportation'",
            "'76 PRODUCTS BELLEVUE','transportation'",
            "'EXXON #4567','transportation'",
            "'MOBIL #234','transportation'",
            "'BP #789 CHI','transportation'",
            "'ARCO #2345','transportation'",
            "'VALERO #7669','transportation'",
            "'SUNOCO #1234','transportation'",
            "'MARATHON #4321','transportation'",
            "'SPEEDWAY #99','transportation'",
            "'PHILLIPS 66','transportation'",
            "'CONOCO STATION','transportation'",
            "'QUIKTRIP #1','transportation'",
            "'WAWA #1','transportation'",
            "'KWIK TRIP','transportation'",
            "'SHEETZ #5','transportation'",
            "'CASEYS GENERAL','transportation'",
            "'MAVERIK GAS','transportation'",
            "'BUC-EES #5','transportation'",
            "'PILOT FLYING J','transportation'",
            "'LOVES TRAVEL STOPS','transportation'",
            // Brand-tied gas
            "'COSTCO GAS #0110','transportation'",
            "'SAMS GAS #1','transportation'",
            "'KROGER GAS','transportation'",
            "'SAFEWAY GAS','transportation'",
            // EV
            "'TESLA SUPERCHARGER FREMONT','transportation'",
            "'ELECTRIFY AMERICA','transportation'",
            "'CHARGEPOINT','transportation'",
            "'EVGO CHARGING','transportation'",
            // Rideshare
            "'UBER TRIP HELP.UBER.COM','transportation'",
            "'LYFT RIDE','transportation'",
            "'WAYMO ONE PHX','transportation'",
            "'OLA CABS BLR','transportation'",
            "'GRAB CAR SG','transportation'",
            "'DIDI RIDER','transportation'",
            "'BOLT TAXI','transportation'",
            "'CABIFY MADRID','transportation'",
            "'GETT TAXI','transportation'",
            "'FREE NOW MUC','transportation'",
            // Tolls / DMV / transit
            "'SDOT PAYBYPHONE PARKIN','transportation'",
            "'WSDOT-GOODTOGO','transportation'",
            "'E-ZPASS NY','transportation'",
            "'FASTRAK BAY','transportation'",
            "'SUNPASS FL','transportation'",
            "'TFL TRAVEL CHARGE','transportation'",
            "'AMTRAK','transportation'",
            "'MTA METROCARD','transportation'",
            "'BART FARE','transportation'",
            // Car rental
            "'ENTERPRISE RENT A CAR','transportation'",
            "'BUDGET RENT A CAR','transportation'",
            "'HERTZ RENT A CAR','transportation'",
            "'AVIS RENT A CAR','transportation'",
            "'NATIONAL RENTAL CAR','transportation'",
            "'SIXT RENT A CAR','transportation'",
            "'ZIPCAR','transportation'",
            "'TURO INC','transportation'",
            "'GETAROUND','transportation'",
            // Auto service
            "'HONDA OF FIFE','transportation'",
            "'TOYOTA OF BELLEVUE','transportation'",
            "'AUTOZONE #1','transportation'",
            "'JIFFY LUBE','transportation'",
            "'PEP BOYS','transportation'",
            "'VALVOLINE INSTANT','transportation'",
            "'DISCOUNT TIRE','transportation'",
            // Parking
            "'SPOTHERO','transportation'",
            "'PARKWHIZ','transportation'",
            "'PARKMOBILE','transportation'",
            "'ACE PARKING','transportation'",
            "'METROPOLIS PARKING','transportation'",
            // Shipping
            "'THE UPS STORE #1234','transportation'",
            "'FEDEX OFFICE','transportation'",
            "'USPS.COM','transportation'"
        })
        void transportation(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // TECH / SaaS / STREAMING
    // =====================================================================
    @Nested
    @DisplayName("Tech / streaming / SaaS")
    class Tech {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "'NETFLIX SUBSCRIPTION','tech'",
            "'SPOTIFY USA NEW YORK','tech'",
            "'HULU SUBSCRIPTION','tech'",
            "'HBO MAX','tech'",
            "'MAX SUBSCRIPTION','tech'",
            "'PEACOCK TV','tech'",
            "'PARAMOUNT+ ANNUAL','tech'",
            "'DISCOVERY+ MONTHLY','tech'",
            "'ESPN+ MONTHLY','tech'",
            "'TIDAL HIFI','tech'",
            "'PANDORA PLUS','tech'",
            "'YOUTUBE MUSIC G.CO/HELPPAY','tech'",
            "'YOUTUBE PREMIUM','tech'",
            "'APPLE MUSIC SUB','tech'",
            "'APPLE TV+ AUTOPAY','tech'",
            "'AMAZON PRIME VIDEO','tech'",
            "'ANTHROPIC SF','tech'",
            "'OPENAI CHATGPT PLUS','tech'",
            "'CLAUDE.AI SUBSCRIPTION','tech'",
            "'HUGGINGFACE BROOKLYN','tech'",
            "'GITHUB.COM','tech'",
            "'GITLAB MEMBERSHIP','tech'",
            "'ADOBE CREATIVE CLOUD','tech'",
            "'CANVA SUBSCRIPTION','tech'",
            "'NOTION LABS','tech'",
            "'FIGMA INC','tech'",
            "'SLACK TECHNOLOGIES','tech'",
            "'ZOOM US','tech'",
            "'1PASSWORD.COM','tech'",
            "'BITWARDEN','tech'",
            "'LASTPASS','tech'",
            "'NORDVPN','tech'",
            "'EXPRESSVPN','tech'",
            "'MULLVAD','tech'",
            "'PROTONMAIL','tech'",
            "'DROPBOX SUBSCRIPTION','tech'",
            "'ICLOUD STORAGE','tech'",
            "'GOOGLE ONE STORAGE','tech'",
            "'MICROSOFT 365 RENEW','tech'",
            "'AUDIBLE MEMBERSHIP','tech'",
            "'TWITCH PRIME','tech'",
            "'PATREON.COM','tech'",
            "'MIDJOURNEY','tech'",
            "'PERPLEXITY AI','tech'",
            "'WALMART+ MEMBER','tech'",
            "'TODOIST','tech'",
            "'RAYCAST','tech'",
            "'TAILSCALE','tech'",
            "'DATADOG INC','tech'",
            "'SUPABASE','tech'",
            "'VERCEL DOMAINS','tech'",
            "'PLAYSTATION PLUS','tech'",
            "'XBOX GAME PASS','tech'",
            "'DISCORD NITRO','tech'",
            "'LINKEDIN PREMIUM','tech'",
            "'DUOLINGO MONTHLY','tech'",
            "'BABBEL ANNUAL','tech'",
            "'CRUNCHYROLL','tech'",
            "'MUBI','tech'",
            "'SHUDDER','tech'",
            "'AMAZON KINDLE','tech'"
        })
        void techBrands(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // FEES + INSURANCE + CHARITY + PAYMENT
    // =====================================================================
    @Nested
    @DisplayName("Fees + Insurance + Charity")
    class FeesAndOthers {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            // Fees
            "'FOREIGN TRANSACTION FEE .40','fees'",
            "'RETURNED PAYMENT FEE','fees'",
            "'LATE FEE','fees'",
            "'ANNUAL MEMBERSHIP FEE','fees'",
            "'SERVICE FEE','fees'",
            "'WIRE TRANSFER FEE','fees'",
            "'OVERDRAFT FEE','fees'",
            "'CONVENIENCE FEE','fees'",
            // Insurance
            "'GEICO INSURANCE','insurance'",
            "'PROGRESSIVE AUTO','insurance'",
            "'ALLSTATE INS','insurance'",
            "'STATE FARM AUTO','insurance'",
            "'USAA AUTO','insurance'",
            "'LIBERTY MUTUAL','insurance'",
            "'NATIONWIDE INSURANCE','insurance'",
            "'KAISER PERMANENTE','insurance'",
            "'BLUE CROSS BLUE SHIELD','insurance'",
            "'AETNA CARE','insurance'",
            "'CIGNA HEALTH','insurance'",
            "'HUMANA INC','insurance'",
            "'LEMONADE INS','insurance'",
            "'HIPPO HOME','insurance'",
            "'TRAVELERS INS','insurance'",
            "'WFGINSURANCE 770-246','insurance'",
            // Charity
            "'GIRL SCOUTS OF THE','charity'",
            "'SALVATION ARMY','charity'",
            "'RED CROSS','charity'",
            "'UNICEF.ORG','charity'",
            "'WORLD VISION','charity'",
            "'HABITAT FOR HUMANITY','charity'",
            "'GOODWILL INDUSTRIES','charity'",
            "'GO FUND ME','charity'",
            "'AMERICAN CANCER SOCIETY','charity'",
            "'MAKE A WISH','charity'",
            // Payment apps as merchant
            "'VENMO * JANE DOE','payment'",
            "'CASH APP* JOHN SMITH','payment'",
            "'ZELLE PAYMENT TO','payment'",
            "'PAYPAL * ACME LLC','payment'"
        })
        void feesInsuranceCharityPayment(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // UTILITIES — power / cable / mobile (global)
    // =====================================================================
    @Nested
    @DisplayName("Utilities — power / cable / mobile (global)")
    class Utilities {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            // Trash / waste
            "'REPUBLIC SERVICES TRASH','utilities'",
            "'WASTE MANAGEMENT','utilities'",
            // Power
            "'PG&E ELECTRIC','utilities'",
            "'PACIFIC GAS AND ELECTRIC','utilities'",
            "'SOUTHERN CALIFORNIA EDISON','utilities'",
            "'SDG&E SAN DIEGO','utilities'",
            "'CONSOLIDATED EDISON','utilities'",
            "'DUKE ENERGY','utilities'",
            "'XCEL ENERGY','utilities'",
            "'NATIONAL GRID','utilities'",
            "'PUGET SOUND ENERGY','utilities'",
            "'NV ENERGY','utilities'",
            "'IDAHO POWER','utilities'",
            "'ALABAMA POWER','utilities'",
            "'FPL FLORIDA POWER','utilities'",
            // Gas
            "'SOCALGAS BILL','utilities'",
            "'NICOR GAS','utilities'",
            "'NW NATURAL','utilities'",
            "'ATMOS ENERGY','utilities'",
            // Cable / internet
            "'COMCAST XFINITY','utilities'",
            "'SPECTRUM INTERNET','utilities'",
            "'COX COMMUNICATIONS','utilities'",
            "'AT&T FIBER','utilities'",
            "'VERIZON FIOS','utilities'",
            "'STARLINK SERVICES','utilities'",
            "'GOOGLE FIBER','utilities'",
            "'CENTURYLINK','utilities'",
            "'FRONTIER COMMUNICATIONS','utilities'",
            // Mobile (US)
            "'VERIZON WIRELESS','utilities'",
            "'AT&T WIRELESS','utilities'",
            "'T-MOBILE','utilities'",
            "'MINT MOBILE','utilities'",
            "'VISIBLE MOBILE','utilities'",
            "'CRICKET WIRELESS','utilities'",
            "'METROPCS','utilities'",
            "'TRACFONE','utilities'",
            // International mobile
            "'AIRTEL PREPAID DEL','utilities'",
            "'JIO PREPAID','utilities'",
            "'VODAFONE UK','utilities'",
            "'EE MOBILE LDN','utilities'",
            "'O2 MOBILE UK','utilities'",
            "'ORANGE MOBILE','utilities'",
            "'ROGERS WIRELESS','utilities'",
            "'BELL MOBILITY','utilities'"
        })
        void utilities(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // HEALTH — pharmacies + telehealth + wellness
    // =====================================================================
    @Nested
    @DisplayName("Health — pharmacy + telehealth")
    class Health {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "'CVS PHARMACY #1234','health'",
            "'WALGREENS #5678','health'",
            "'RITE AID PHARMACY','health'",
            "'DUANE READE','health'",
            "'COSTCO PHARMACY','health'",
            "'WALMART PHARMACY','health'",
            "'BARTELL DRUGS','health'",
            "'ONE MEDICAL','health'",
            "'FORWARD HEALTH','health'",
            "'CARBON HEALTH','health'",
            "'ZOCDOC','health'",
            "'TELADOC HEALTH','health'",
            "'BETTERHELP','health'",
            "'TALKSPACE','health'",
            "'HIMS HEALTH','health'",
            "'NURX','health'",
            "'LENSCRAFTERS','health'",
            "'WARBY PARKER','health'",
            "'SEPHORA','health'",
            "'ULTA BEAUTY','health'",
            "'SUPERCUTS','health'",
            "'GREAT CLIPS','health'",
            "'EUROPEAN WAX CENTER','health'",
            "'MASSAGE ENVY','health'",
            "'EQUINOX FITNESS','health'",
            "'ORANGE THEORY','health'",
            "'24 HOUR FITNESS','health'",
            "'PLANET FITNESS','health'",
            "'F45 TRAINING','health'",
            "'PELOTON','health'",
            "'CLASSPASS','health'"
        })
        void healthMerchants(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // SHOPPING — apparel + electronics + general retail
    // =====================================================================
    @Nested
    @DisplayName("Shopping — apparel + electronics + general")
    class Shopping {
        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
            "'TARGET #00025841','shopping'",
            "'WALMART STORE','shopping'",
            "'H&M BELLEVUE','shopping'",
            "'OLD NAVY','shopping'",
            "'GAP STORE','shopping'",
            "'UNIQLO','shopping'",
            "'ZARA WESTFIELD','shopping'",
            "'FOREVER 21','shopping'",
            "'LULULEMON','shopping'",
            "'ATHLETA','shopping'",
            "'PATAGONIA','shopping'",
            "'THE NORTH FACE','shopping'",
            "'REI COOP','shopping'",
            "'EDDIE BAUER','shopping'",
            "'NORDSTROM RACK','shopping'",
            "'MACYS #1234','shopping'",
            "'ROSS DRESS FOR LESS','shopping'",
            "'TJ MAXX','shopping'",
            "'MARSHALLS','shopping'",
            "'HOMEGOODS','shopping'",
            "'BEST BUY #5','shopping'",
            "'MICRO CENTER','shopping'",
            "'APPLE STORE','shopping'",
            "'B&H PHOTO','shopping'",
            "'NEWEGG','shopping'",
            "'AMAZON.COM','shopping'",
            "'EBAY','shopping'",
            "'ETSY.COM','shopping'",
            "'WAYFAIR','shopping'",
            "'JOANN FABRIC','shopping'",
            "'HOBBY LOBBY','shopping'",
            "'BARNES AND NOBLE','shopping'",
            "'OFFICE DEPOT','shopping'",
            "'STAPLES STORE','shopping'",
            "'BUILD-A-BEAR','shopping'",
            "'LEGO STORE','shopping'"
        })
        void shoppingMerchants(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // HOME IMPROVEMENT
    // =====================================================================
    @Nested
    @DisplayName("Home improvement")
    class HomeImprovement {
        @ParameterizedTest(name = "[{index}] {0} → home improvement")
        @CsvSource({
            "'THE HOME DEPOT #4704','home improvement'",
            "'LOWES #00907','home improvement'",
            "'SHERWIN-WILLIAMS','home improvement'",
            "'ACE HARDWARE','home improvement'",
            "'TRUE VALUE','home improvement'",
            "'MENARDS','home improvement'",
            "'FERGUSON PLUMBING','home improvement'",
            "'FLOOR AND DECOR','home improvement'",
            "'POTTERY BARN','home improvement'",
            "'WEST ELM','home improvement'",
            "'CRATE AND BARREL','home improvement'",
            "'IKEA RENTON','home improvement'",
            "'BED BATH AND BEYOND','home improvement'"
        })
        void homeImprovement(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // ENTERTAINMENT
    // =====================================================================
    @Nested
    @DisplayName("Entertainment")
    class Entertainment {
        @ParameterizedTest(name = "[{index}] {0} → entertainment")
        @CsvSource({
            "'AMC THEATRE 1234','entertainment'",
            "'REGAL CINEMAS','entertainment'",
            "'CINEMARK 16','entertainment'",
            "'ALAMO DRAFTHOUSE','entertainment'",
            "'TOPGOLF','entertainment'",
            "'DAVE AND BUSTER','entertainment'",
            "'MAIN EVENT','entertainment'",
            "'SIX FLAGS','entertainment'",
            "'UNIVERSAL STUDIOS','entertainment'",
            "'DISNEYLAND','entertainment'",
            "'DISNEY WORLD','entertainment'",
            "'SEAWORLD','entertainment'",
            "'TICKETMASTER','entertainment'",
            "'STUBHUB.COM','entertainment'",
            "'SEATGEEK','entertainment'",
            "'VIVID SEATS','entertainment'",
            "'AXS.COM','entertainment'",
            "'NATIONAL PARK','entertainment'",
            "'STATE PARK','entertainment'"
        })
        void entertainmentMerchants(String desc, String cat) {
            expect(desc, cat);
        }
    }

    // =====================================================================
    // EDUCATION
    // =====================================================================
    @Nested
    @DisplayName("Education")
    class Education {
        @ParameterizedTest(name = "[{index}] {0} → education")
        @CsvSource({
            "'EXAMFX','education'",
            "'PSI EXAMS','education'",
            "'KAPLAN TEST PREP','education'",
            "'PEARSON VUE','education'",
            "'PROMETRIC TEST','education'",
            "'ETS-TOEFL','education'",
            "'ETS-GRE','education'",
            "'MAGOOSH','education'",
            "'PRINCETON REVIEW','education'",
            "'KHAN ACADEMY','education'",
            "'WYZANT TUTOR','education'",
            "'OUTSCHOOL','education'",
            "'BELLEVUE SCHOOL DISTRI','education'",
            "'TYEE MIDDLE','education'",
            "'PAYPAMS.COM','education'",
            "'AAMC EXAM','education'",
            "'ANKIHUB CORE','education'"
        })
        void education(String desc, String cat) {
            expect(desc, cat);
        }
    }
}
