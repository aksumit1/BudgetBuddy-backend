# Merchant Category Code (MCC) lookup — plan and deferral

## Why this would move the needle

Credit card networks (Visa, Mastercard, Amex, Discover) assign every
merchant a 4-digit **Merchant Category Code** — a stable, authoritative
identifier for what kind of business they are. 5411 = grocery store,
5812 = restaurant, 5541 = gas station, 4899 = cable/streaming, etc.

If we had per-transaction MCC, category inference would be near-perfect
for the 80% of transactions that go through card networks. Today we get
the merchant text — "STARBUCKS #1234 SEATTLE WA" — and have to guess.
With MCC = 5814 ("fast-food restaurant") we wouldn't have to guess.

## Why we don't have it today

Bank statements **almost never print the MCC** in human-readable text.
It's in the ISO 8583 transaction record the bank consumes internally,
but by the time it reaches a statement PDF it's been stripped. Plaid
exposes it for some categories but not reliably.

So "use the MCC" becomes "attach an MCC to each transaction by looking
up the merchant name in a merchant→MCC table." That's a separate
engineering project.

## What it takes to ship

### 1. Data source for merchant → MCC
Candidates, ranked by cost / freshness:

| Source | Cost | Freshness | Licence |
|---|---|---|---|
| Plaid `/categories/get` | free with Plaid | daily | Plaid ToS |
| Visa Global Brand Merchant List | paid | monthly | commercial |
| MasterCard Merchant Location Feed | paid | weekly | commercial |
| Scraped Yelp / Google Places (merchant → category → MCC-ish) | infrastructure | stale | ToS risk |
| Curated internal CSV (like `MerchantCategoryDataService` today) | labor | whatever we ship | ours |

The most pragmatic starting point is to **extend the existing
`MerchantCategoryDataService`** with an MCC column. We already maintain
a merchant→category map; adding a third column (MCC) is zero additional
infrastructure and lets us bootstrap.

### 2. Lookup service
Two modes:

1. **Exact match**: normalize the merchant string (lowercase, strip
   store numbers + payment-processor prefixes — already done by
   `TextNormalizer.cleanMerchantText`) and look up in the table.
2. **Fuzzy match**: if exact misses, run the existing `FuzzyMatchingService`
   (Jaro-Winkler) against the table keys. Accept if confidence ≥ 0.85.

### 3. Category mapping
Static `MCC → category` table. These are well-documented:

```
5411 → groceries
5412 → groceries (convenience)
5812 → dining
5814 → dining (fast food)
5541 → transportation (fuel)
5542 → transportation (fuel — automated)
5968 → subscriptions (direct marketing)
4899 → subscriptions (cable / streaming)
...
```

Roughly 400 MCCs total; maybe 50 cover 95% of consumer spending.

### 4. Integration into the pipeline

`CategoryDetectionManager.detectCategory` already has a tiered resolution
(custom mapping → merchant lookup → fuzzy → semantic → fallback). MCC
would slot in as a high-confidence signal **right after** custom mapping:

```
 1. Custom user mapping  (confidence 1.0)
 2. MCC lookup           (confidence 0.95)   ← new
 3. Merchant-name table  (confidence 0.85)
 4. Fuzzy match          (confidence 0.70)
 5. Semantic / BERT      (variable)
 6. Fallback "other"     (confidence 0.30)
```

## Why it's deferred for now

1. **Data source is the real cost.** Writing the lookup service is half
   a day; sourcing and licensing the MCC data is the multi-week part.
2. **The current template + fuzzy + semantic pipeline is surprisingly
   good** — our fixture-tested banks are showing >95% categorization
   accuracy on synthetic samples without MCC. Real-sample evaluation
   is needed before we know MCC is the bottleneck.
3. **Plaid alternative.** Users connecting via Plaid already get the
   Plaid category taxonomy, which is effectively MCC-informed. Users
   importing PDFs/CSVs are the ones who'd benefit — but that's a
   subset of the user base.

## When to revisit

- Telemetry (`/api/admin/pdf-parse-health` + the iOS user-correction
  feedback loop) shows a specific category-accuracy bottleneck that
  better merchant typing would fix.
- We sign a Plaid contract and get `/categories/get` data for free.
- A user survey reports categorization as the #1 import pain point.

Until then, MCC stays a planned future workstream rather than a shipped
feature.
