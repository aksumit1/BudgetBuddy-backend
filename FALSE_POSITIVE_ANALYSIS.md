# False Positive Analysis

## Test Case: False Positive Detection

**Query**: `"agarwal sumit kumar platinum walmart credit wmt plus sep"`  
**Candidate**: `"summit at snoqualmie"`  
**Expected**: Should NOT match (false positive - different merchants)

---

## Algorithm Scores Analysis

### Normalization:
- Query normalized: `agarwal sumit kumar platinum walmart credit wmt plus sep`
- Candidate normalized: `summit at snoqualmie`

### Token Breakdown:
- Query tokens: `[agarwal, sumit, kumar, platinum, walmart, credit, wmt, plus, sep]` (9 tokens)
- Candidate tokens: `[summit, at, snoqualmie]` (3 tokens)

---

## Algorithm Scores:

### 1. Jaro-Winkler Score:
- Comparing: `"agarwal sumit kumar platinum walmart credit wmt plus sep"` (54 chars) vs `"summit at snoqualmie"` (19 chars)
- Length difference: 35 characters
- **Score: ~0.25-0.30** (25-30%)
- ✅ **LOW** - Good for false positive detection

### 2. Full Token Score:
- Token matching:
  - `sumit` → `summit` (Jaro-Winkler: ~0.95) ✅ Match (≥0.7 threshold)
  - `at` → `at` (exact match) ✅ Match
  - Other tokens: No matches
- Matched tokens: `{sumit, summit, at, at}` (4 tokens)
- Union: `{agarwal, sumit, kumar, platinum, walmart, credit, wmt, plus, sep, summit, at, snoqualmie}` (12 unique tokens)
- Base similarity: `4 / 12 = 0.3333`
- Average token similarity: `(0.95 + 1.0) / 2 = 0.975`
- Final score: `(0.3333 * 0.6) + (0.975 * 0.4) = 0.2000 + 0.3900 = 0.5900` (59%)
- ⚠️ **MEDIUM-HIGH** - Could cause false positive

### 3. Substring Score (IMPROVED with word boundary matching):
- Does query contain candidate? NO
- Does candidate contain query? NO
- Token containment check (word boundary matching):
  - `summit` as whole word in query? NO (only `sumit` exists, not `summit`)
  - `at` as whole word in query? NO (not in query token set)
  - `snoqualmie` as whole word in query? NO
  - Substring fallback (tokens ≥4 chars): `summit` (6 chars) in query? NO (only `sumit` exists)
- **Score: 0.0** (0%)
- ✅ **LOWEST** - Best for false positive detection
- ✅ **IMPROVED**: Word boundary matching prevents "at" from matching inside "walmart" or "platinum"

### 4. Token Jaccard Score:
- Intersection: `{at}` (1 token - "at" appears in both, but "sumit" ≠ "summit")
- Union: `{agarwal, sumit, kumar, platinum, walmart, credit, wmt, plus, sep, summit, at, snoqualmie}` (12 tokens)
- Jaccard: `1 / 12 = 0.0833` (8.33%)
- Length boost: Minimal (short matching token)
- **Score: ~0.10** (10%)
- ✅ **LOW** - Good for false positive detection

---

## Combined Scores:

### Old Algorithm (50% Jaro-Winkler + 50% Full Token):
- `(0.28 * 0.5) + (0.59 * 0.5) = 0.14 + 0.295 = 0.435` (43.5%)
- ⚠️ **BELOW 0.50 threshold** - Would reject (good)

### New Algorithm (Adaptive - Jaro-Winkler < 0.3):
- `(0.28 * 0.2) + (0.59 * 0.3) + (0.0 * 0.3) + (0.10 * 0.2)`
- `= 0.056 + 0.177 + 0.0 + 0.02 = 0.253` (25.3%)
- ✅ **WELL BELOW 0.50 threshold** - Would reject (excellent)

---

## Summary

| Algorithm | Score | False Positive Risk |
|-----------|-------|---------------------|
| **Jaro-Winkler** | 0.28 (28%) | ✅ Low risk |
| **Full Token** | 0.59 (59%) | ⚠️ **HIGH RISK** - Could match |
| **Substring** | **0.0 (0%)** | ✅ **LOWEST RISK** - Best |
| **Token Jaccard** | 0.10 (10%) | ✅ Low risk |
| **Old Combined** | 0.435 (43.5%) | ✅ Below threshold |
| **New Combined** | **0.253 (25.3%)** | ✅ **BEST** - Well below threshold |

---

## Key Findings:

1. ✅ **Substring algorithm gives the LOWEST score (0.0)** - Best for false positive detection
2. ⚠️ **Full Token gives the HIGHEST score (0.59)** - Could cause false positive if used alone
3. ✅ **New adaptive algorithm (0.253)** is better than old (0.435) for false positive prevention
4. ✅ **Substring algorithm prevents false positives** because "summit at snoqualmie" is NOT contained in the query

---

## Why Substring Prevents False Positives:

The substring algorithm only gives high scores when:
- The candidate merchant name is **fully contained** in the query, OR
- Candidate tokens are **contained** in the query

In this case:
- `"summit at snoqualmie"` is NOT contained in `"agarwal sumit kumar platinum walmart credit wmt plus sep"`
- Even though `"sumit"` (typo) is similar to `"summit"`, the substring algorithm requires exact containment
- Result: **0.0 score** - correctly rejects the false positive

---

## Recommendation:

The **substring algorithm is the best for preventing false positives** in this case because:
1. It requires exact containment (or token containment)
2. It doesn't match on typos or similar-sounding words
3. It gives 0.0 when there's no containment, which helps keep the combined score low

The **new adaptive algorithm (25.3%)** is significantly better than the old algorithm (43.5%) for false positive prevention, thanks to the substring algorithm's 0.0 score.
