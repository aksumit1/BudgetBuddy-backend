# Pattern learnings — what real bank statements actually look like

This is a living reference for anyone writing a new YAML template. It
codifies seven years of "surprise, this bank does it differently" lessons
from the Java `parsePattern1..7` methods so new templates don't repeat
the same mistakes.

Each section describes a *shape* that real banks produce and what that
shape requires of a template.

---

## 1. Single-line "date + description + amount"

**Who ships this:** Chase checking, Wells Fargo checking, Ally, Chime, SoFi,
TD, PNC, most US online banks.

**Shape:**
```
03/15 AMZN Mktp US *A1B2C3D4                     -34.99
```

**Learnings:**
- The amount sign is often explicit (`-34.99`) but not always — some banks
  render withdrawals as positive and rely on column position. A template
  that assumes signed amounts will misclassify half of them.
- A **trailing running-balance column** is common. Templates must make the
  balance column optional (`(?:\s+[\d,.]+)?$`) or they'll reject every
  other row.
- Merchant names frequently contain the city/state suffix
  (`SEATTLE WA`) — leave that in the description; `TextNormalizer`
  cleans it later.

**From Java:** this is `parsePattern1` at `PDFImportService.java:3616`.

---

## 2. Prefix-tolerant layouts (reward / bonus / cashback lines)

**Who ships this:** Amex reward statements, Chase Ultimate Rewards credit
lines, Capital One Savor cashback.

**Shape:**
```
1% Cashback Bonus +$0.06 10/06 AMAZON.COM
5% Ultimate Rewards on Travel +$15.00 10/12 DELTA 0061234
```

**Learnings:**
- The date is **not at column 0** — this breaks every `^(?<date>...)` template.
- The amount appears as `+$X.XX` with a literal plus sign, *before* the
  date. Your regex must hook on the amount first, then find the date
  within the rest of the line.
- These are always positive (credits). `signConvention: credit-positive`
  flips them to our "negative = credit on a credit card" storage convention.

**Template pattern** (copy-paste for new banks with this shape):
```yaml
lineRegex: '^\s*(?<description>.+?\+\$(?<amount>\d{1,3}(?:,\d{3})*(?:\.\d{2}))).*?(?<date>\d{1,2}/\d{1,2})\b'
```

**From Java:** this is `parsePattern2` at `PDFImportService.java` — kept in
Java historically because the descriptive prefix varied too much to
template. YAML absorbs it now via the pattern above.

---

## 3. Two-date layouts (transaction date + posting date)

**Who ships this:** All major US credit cards (Chase Sapphire/Freedom/Ink,
BoA, Wells, Citi, U.S. Bank, some Capital One Spark cards).

**Shape:**
```
03/15 03/16 AMZN MARKETPLACE SEATTLE WA               34.99
```

**Learnings:**
- First date is transaction-date (when you swiped); second is posting-date
  (when it settled). We use **posting-date** as the canonical row date
  because that's what most customers see in their bank UI.
- Merchant text runs into city/state — the location regex `[A-Z][A-Z\s]{1,20}`
  that the Java `parsePattern5` uses is too strict (fails on lowercase
  merchants) and not necessary; a greedy `.+?` on description works better.
- Amount is typically unsigned on CC purchases → `signConvention: credit-positive`
  flips to our negative = credit-card-charge convention.

**From Java:** `parsePattern5` at `PDFImportService.java:3833`, used by
Costco/Walmart/Dollar Tree debit statements and the Java CC patterns.

---

## 4. Multi-line transaction blocks (Amex standard statements)

**Who ships this:** American Express personal + business cards.

**Shape** (3-5 physical lines per transaction):
```
11/27/25 AGARWAL SUMIT KUMAR Platinum Uber One Credit
 UBER ONE
 -$9.99 ⧫
```

**Learnings:**
- This **cannot be expressed declaratively** in a single-line regex. A
  template would need stateful line accumulation across the PDFBox output
  — not something YAML supports.
- The ⧫ diamond marks foreign-currency transactions; strip it upstream,
  don't try to match it.
- The first line is always a date + merchant-header; the last line is always
  an amount-only. Lines between are descriptive detail.

**From Java:** `parsePattern7` at `PDFImportService.java:3723`, **75
dedicated tests** in `PDFImportServicePattern7Test`. Stays in Java.

**Rule of thumb:** if your target bank's statements have multi-line
transactions, don't add a YAML template — file a follow-up to extend
Pattern 7 or add a sibling Java parser.

---

## 5. Card-last-4 + transaction-ID prefix (WSDOT, some business cards)

**Who ships this:** WSDOT (Washington State DOT tolls), some business
corporate cards.

**Shape:**
```
2024 03/15 03/16 6789ABC0123 COSTCO GAS SHORELINE WA   $45.20
```

**Learnings:**
- Some card issuers put a 4-digit year prefix, then two dates, then a
  transaction ID, then the description, then location, then amount. The
  `parsePattern4` Java regex hardcodes `[A-Z][A-Z\s]{1,20}` for location
  which fails on mixed-case merchants.
- Narrow enough use case that a YAML template is lower-ROI than leaving
  the Java pattern in place.

**From Java:** `parsePattern4`. Stays in Java — this is a genuinely
idiosyncratic shape.

---

## 6. UK-style "Paid out | Paid in | Balance" columns

**Who ships this:** HSBC UK, Barclays UK, Lloyds, NatWest, most UK high-street banks.

**Shape:**
```
15 Mar 2024 WAITROSE HIGH ST LONDON        42.18          1,341.77
15 Mar 2024 BT SALARY                                 2,500.00    3,841.77
```

**Learnings:**
- Date format is **DD MMM YYYY** (`15 Mar 2024`) — not numeric. Use
  `dateFormat: "d MMM yyyy"`.
- Amounts are **£-denominated** and unsigned.
- Direction is encoded by **column**: the Paid-out (money leaving) column
  is separate from the Paid-in (money arriving) column. Either one is
  populated per row, not both.
- Because a declarative regex can't easily say "capture column 3 OR
  column 4, never both", we ship TWO layouts per UK bank — one matching
  "description + amount + balance" (Paid-out) with `signConvention: negate`,
  one matching "description + amount" (Paid-in) with `signConvention: as-is`.

**From Java:** no Java pattern specifically for UK layouts — the
`EnhancedPatternMatcher` fuzzy fallback has been carrying them. YAML now
gives us declarative coverage.

---

## 7. Indian-bank "Narration | Withdrawal | Deposit | Balance"

**Who ships this:** HDFC, ICICI, SBI, Axis, Kotak, Yes Bank, most Indian
scheduled banks.

**Shape:**
```
15/03/24 UPI-PAYTM-SOMEMERCHANT@paytm-UPI/123456         42.18              3,841.77
15/03/24 NEFT CR-ACME CORP-SALARY                                 45,000.00   48,841.77
```

**Learnings:**
- Dates are **DD/MM/YY** or **DD/MM/YYYY** — the single most common
  cross-locale parse bug is treating these as MM/DD. Use `dateFormat: "d/M/yy"`.
- Amounts use Indian lakh grouping: `1,23,456.78` (inner comma every 2
  digits). Our amount regex `\d{1,3}(?:,\d{1,3})*(?:\.\d{2})` accepts this.
- Currency symbol is `₹` (U+20B9) — always UTF-8, always present on credit
  card statements, often absent on savings.
- Transaction **direction is encoded in the narration prefix**:
  `NEFT CR`, `IMPS CR`, `UPI CR`, `SALARY`, `REFUND` → credit/money in.
  `NEFT DR`, `POS`, `ATM`, `UPI`, `CHRG` → debit/money out.
- Credit card statements add a trailing `Dr` / `Cr` suffix which we strip
  via `(?:Dr|Cr)?` at the end of the regex.

**From Java:** no existing pattern — registry is the first coverage for
Indian banks.

---

## 8. Payment / fee / interest lines

Every statement includes rows that aren't "purchases" but still need to
be imported:

```
AUTOMATIC PAYMENT - THANK YOU                         -500.00
INTEREST CHARGE ON PURCHASES                            19.42
ANNUAL MEMBERSHIP FEE                                   95.00
LATE PAYMENT FEE                                        40.00
FOREIGN TRANSACTION FEE                                  0.75
MONTHLY MAINTENANCE FEE                                 12.00
NSF RETURNED CHECK FEE                                  35.00
ATM WITHDRAWAL FEE                                       2.50
```

**Learnings:**
- No special templates needed — these match the normal single-line shape
  because the "merchant description" fills the slot where the merchant
  would be.
- `TextNormalizer.cleanMerchantText` + backend categorization assigns
  them to the right category (`fee`, `interest`, `payment`).
- Do **not** write a template that tries to match "ANNUAL MEMBERSHIP FEE"
  specifically — it's an anti-pattern. Categorization is downstream's job.

---

## 9. Things that break templates

- **Mid-statement section headers** ("Purchases", "Cash Advances",
  "Previous Balance") can match loose regexes. Every template's regex
  should require both a date AND an amount; a header with just text will
  fail to match.
- **Continued-on-next-page pages** — handled by the new
  `stitchContinuationLines` pre-pass in `PDFImportService`. Templates
  themselves don't need to worry about line-breaks.
- **Scanned PDFs** — handled by `PdfOcrService` (optional). Templates
  don't need to worry about OCR quirks directly; Tesseract output is
  usually clean enough that the same regex works.
- **Rent-A-Center-style "installment plan" lines** (Apple Card monthly
  installments, Affirm/Afterpay) — these have non-standard formats that
  vary per issuer. Defer to loose fallback; add a dedicated layout only
  if one issuer shows up hot in `PDF_TEMPLATE_MISS` telemetry.

---

## How to add a new institution

1. **Get a real statement PDF.** Ideally 3, across different months —
   layouts drift.
2. **Extract text** with `pdftotext` or PDFBox; look at the raw line
   shapes.
3. **Identify which section of this doc matches** — most banks fit into
   1-3 of the 9 shapes above.
4. **Write the YAML** with `status: UNVERIFIED`.
5. **Add a fixture + expected JSON** under
   `src/test/resources/pdf-template-fixtures/<bank-slug>/`.
6. **Promote status to VALIDATED** once the test passes.

Take the time to read this document before adding the next template.
You'll avoid 3-4 hours of debugging every single time.
