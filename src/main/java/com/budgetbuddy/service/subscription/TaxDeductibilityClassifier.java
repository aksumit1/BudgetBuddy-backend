package com.budgetbuddy.service.subscription;

import com.budgetbuddy.model.Subscription;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Classifies a subscription's typical tax-deductibility for a business
 * user. The result is informational only — it surfaces as a chip in the
 * iOS subscription list so a freelancer can see at a glance "Adobe is
 * likely deductible, Netflix is not" before exporting their year-end
 * subscription report.
 *
 * <p>Output is one of:
 * <ul>
 *   <li>{@code FULL} — productivity / SaaS / business tools usually 100% deductible
 *   <li>{@code PARTIAL} — services with mixed business/personal use
 *       (e.g. phone, internet, cloud storage). The user has to apportion.
 *   <li>{@code NONE} — personal entertainment (streaming, music, gaming).
 *   <li>{@code UNKNOWN} — couldn't classify; show "review with accountant".
 * </ul>
 *
 * <p>Heuristic only — we never assert a tax-advice judgement. The iOS
 * surface explicitly labels this as "estimate, confirm with your
 * accountant" wherever it's shown.
 */
@Component
public class TaxDeductibilityClassifier {

    /** SaaS / productivity merchants that are usually 100% deductible. */
    private static final Set<String> FULL_HINTS =
            Set.of(
                    "adobe",
                    "microsoft 365",
                    "office 365",
                    "notion",
                    "figma",
                    "jetbrains",
                    "github",
                    "linkedin",
                    "linkedin premium",
                    "quickbooks",
                    "freshbooks",
                    "wave",
                    "calendly",
                    "zoom",
                    "slack",
                    "dropbox business",
                    "google workspace",
                    "1password",
                    "lastpass",
                    "expensify",
                    "shopify",
                    "stripe",
                    "mailchimp",
                    "intercom");

    /** Mixed personal/business — apportion. */
    private static final Set<String> PARTIAL_HINTS =
            Set.of(
                    "phone",
                    "internet",
                    "verizon",
                    "att",
                    "comcast",
                    "xfinity",
                    "icloud",
                    "google drive",
                    "google one",
                    "onedrive",
                    "dropbox");

    /** Personal entertainment. */
    private static final Set<String> NONE_HINTS =
            Set.of(
                    "netflix",
                    "hulu",
                    "disney",
                    "hbo",
                    "max",
                    "paramount",
                    "peacock",
                    "apple tv",
                    "spotify",
                    "apple music",
                    "tidal",
                    "youtube music",
                    "amazon music",
                    "audible",
                    "peloton",
                    "apple fitness",
                    "strava",
                    "playstation plus",
                    "xbox game pass",
                    "nintendo switch online");

    public Deductibility classify(final Subscription sub) {
        if (sub == null || sub.getMerchantName() == null) return Deductibility.UNKNOWN;
        final String m = sub.getMerchantName().toLowerCase(Locale.ROOT);
        if (matchesAny(m, FULL_HINTS)) return Deductibility.FULL;
        if (matchesAny(m, NONE_HINTS)) return Deductibility.NONE;
        if (matchesAny(m, PARTIAL_HINTS)) return Deductibility.PARTIAL;
        return Deductibility.UNKNOWN;
    }

    private static boolean matchesAny(final String merchant, final Set<String> hints) {
        for (final String h : hints) {
            if (merchant.contains(h)) return true;
        }
        return false;
    }

    public enum Deductibility {
        FULL,
        PARTIAL,
        NONE,
        UNKNOWN
    }
}
