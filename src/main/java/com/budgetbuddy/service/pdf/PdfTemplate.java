package com.budgetbuddy.service.pdf;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One financial institution's declarative PDF parse rules.
 *
 * <h3>Shape</h3>
 *
 * A single institution can produce wildly different statement layouts depending on the product
 * (checking vs credit card vs investment), so {@link PdfTemplate} holds <em>multiple</em> named
 * {@link Layout}s. At parse time the registry tries every layout in order; first match wins for a
 * given line.
 *
 * <h3>Status</h3>
 *
 * Templates come from one of two sources:
 *
 * <ul>
 *   <li>{@code unverified} — regex written from general knowledge of how a bank's statements
 *       usually look. Good starting point; expected to match ~60-80% of real rows. Must be
 *       validated against real samples before promotion.
 *   <li>{@code validated} — exercised against a real anonymised statement and passing a snapshot
 *       test. Safe to rely on.
 *   <li>{@code production} — multiple real-sample validations across customers + minimum-sample
 *       gate. Promoted by the team.
 * </ul>
 *
 * Unverified templates still run (failing them outright would defeat the point of shipping them
 * early), but their matches are logged at INFO with their status so mismatches with real data are
 * visible.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances")
@JsonIgnoreProperties(ignoreUnknown = true)
public class PdfTemplate {

    public enum Status {
        UNVERIFIED,
        VALIDATED,
        PRODUCTION;

        /** Accept case-insensitive values from YAML: "unverified", "UNVERIFIED", etc. */
        @JsonCreator
        public static Status fromString(final String value) {
            if (value == null) {
                return UNVERIFIED;
            }
            return Status.valueOf(value.trim().toUpperCase().replace('-', '_'));
        }
    }

    public enum SignConvention {
        AS_IS, // keep the sign as parsed from the line
        NEGATE, // flip the sign (checking statements that render money-out positive)
        CREDIT_POSITIVE; // credit cards: purchases positive, payments negative — flip to our

        // convention

        /** Accept YAML-friendly forms: "as-is", "AS_IS", "credit-positive", "CREDIT_POSITIVE". */
        @JsonCreator
        public static SignConvention fromString(final String value) {
            if (value == null) {
                return AS_IS;
            }
            return SignConvention.valueOf(value.trim().toUpperCase().replace('-', '_'));
        }
    }

    // ---- top-level institution identity ----

    private String id;
    private String institution;
    private String description;
    private Status status = Status.UNVERIFIED;
    private List<Layout> layouts = Collections.emptyList();

    // ---- per-layout (one bank can have many) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    @SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
    public static class Layout {
        private String name; // e.g. "checking-single-line"
        private String accountType; // optional: "checking" | "savings" | "credit" | "investment"
        private String lineRegex; // named groups: date, description, amount
        private String dateFormat; // e.g. "M/d", "yyyy-MM-dd"; defaults to PDF auto-detection
        private SignConvention signConvention = SignConvention.AS_IS;
        private BigDecimal minAmount = new BigDecimal("0.01");

        @JsonIgnore private Pattern compiledPattern;
        @JsonIgnore private DateTimeFormatter compiledDateFormatter;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getAccountType() {
            return accountType;
        }

        public void setAccountType(final String accountType) {
            this.accountType = accountType;
        }

        public String getLineRegex() {
            return lineRegex;
        }

        public void setLineRegex(final String lineRegex) {
            this.lineRegex = lineRegex;
            this.compiledPattern = null;
        }

        public String getDateFormat() {
            return dateFormat;
        }

        public void setDateFormat(final String dateFormat) {
            this.dateFormat = dateFormat;
            this.compiledDateFormatter = null;
        }

        public SignConvention getSignConvention() {
            return signConvention;
        }

        public void setSignConvention(final SignConvention signConvention) {
            this.signConvention = signConvention;
        }

        public BigDecimal getMinAmount() {
            return minAmount;
        }

        public void setMinAmount(final BigDecimal minAmount) {
            this.minAmount = minAmount;
        }

        /** Validate that this layout is well-formed enough to run. */
        public boolean validate() {
            if (lineRegex == null || lineRegex.isBlank()) {
                return false;
            }
            if (name == null || name.isBlank()) {
                return false;
            }
            try {
                Pattern.compile(lineRegex);
                // The three named groups are required.
                for (final String requiredGroup : new String[] {"date", "description", "amount"}) {
                    if (!lineRegex.contains("(?<" + requiredGroup + ">")) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Try to parse one physical line with this layout. Null if it doesn't match. */
        public Map<String, String> apply(final String line, final Integer inferredYear, final PdfTemplate owner) {
            if (compiledPattern == null) {
                compiledPattern = Pattern.compile(lineRegex);
            }
            final Matcher m = compiledPattern.matcher(line);
            if (!m.find()) {
                return null;
            }
            String date = safeGroup(m, "date");
            final String description = safeGroup(m, "description");
            final String amount = safeGroup(m, "amount");
            if (date == null || description == null || amount == null) {
                return null;
            }

            if (dateFormat != null && !dateFormat.isBlank()) {
                if (compiledDateFormatter == null) {
                    try {
                        // If the declared format has no year token and we have an
                        // inferredYear, compile a year-aware sibling formatter
                        // (e.g. "M/d" → "M/d/yyyy") so we can splice the year in
                        // and parse successfully. Without this fix the "M/d" +
                        // inferredYear combo produced DateTimeParseException.
                        final String effectivePattern =
                                dateFormat.toLowerCase().contains("y")
                                        ? dateFormat
                                        : dateFormat + "/yyyy";
                        compiledDateFormatter = DateTimeFormatter.ofPattern(effectivePattern);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                }
                try {
                    final String expanded = expandYearIfNeeded(date, inferredYear);
                    final LocalDate parsed = LocalDate.parse(expanded, compiledDateFormatter);
                    date = parsed.toString();
                } catch (DateTimeParseException ex) {
                    return null;
                }
            }

            final Map<String, String> row = new HashMap<>();
            row.put("date", date);
            row.put("description", description.trim());
            row.put("amount", amount);
            row.put("_templateId", owner.id);
            row.put("_layoutName", name);
            row.put("_templateStatus", owner.status.name());
            return row;
        }

        private static String safeGroup(final Matcher m, final String name) {
            try {
                return m.group(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private String expandYearIfNeeded(final String rawDate, final Integer inferredYear) {
            if (inferredYear == null || dateFormat == null) {
                return rawDate;
            }
            final String df = dateFormat.toLowerCase();
            if (df.contains("y")) {
                return rawDate;
            }
            return rawDate + "/" + inferredYear;
        }
    }

    // ---- template-level getters / setters ----

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getInstitution() {
        return institution;
    }

    public void setInstitution(final String institution) {
        this.institution = institution;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(final Status status) {
        this.status = status;
    }

    public List<Layout> getLayouts() {
        return layouts;
    }

    public void setLayouts(final List<Layout> layouts) {
        this.layouts = layouts != null ? layouts : Collections.emptyList();
    }

    /** Template is valid if it has an id, at least one layout, and every layout parses. */
    public boolean validate() {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (layouts == null || layouts.isEmpty()) {
            return false;
        }
        for (final Layout layout : layouts) {
            if (!layout.validate()) {
                return false;
            }
        }
        return true;
    }

    /** Try every layout in order. Return the first row that matches. */
    public Map<String, String> apply(final String line, final Integer inferredYear) {
        for (final Layout layout : layouts) {
            final Map<String, String> row = layout.apply(line, inferredYear, this);
            if (row != null) {
                return row;
            }
        }
        return null;
    }
}
