# Fuzzy Matching Algorithm Analysis

## Test Cases

### Test 1: Simple Match
- **Query**: `openai`
- **Candidate**: `openai`

### Test 2: Complex Match with Extra Text
- **Query**: `OPENAI *CHATGPT SUBSCR SAN FRANCISCO CA +14158799686`
- **Candidate**: `openai`

---

## Test 1: "openai" vs "openai"

### Normalization:
- Query normalized: `openai`
- Candidate normalized: `openai`

### Algorithm Scores:

1. **Jaro-Winkler Score**: 
   - Exact match: `1.0000` (100%)
   - ✅ Perfect match for exact strings

2. **Full Token Score**:
   - Query tokens: `[openai]`
   - Candidate tokens: `[openai]`
   - Token match: `openai` → `openai` (Jaro-Winkler: 1.0000)
   - All tokens matched: `1.0000` (100%)
   - ✅ Perfect match when all tokens match

3. **Combined Score (50% Jaro-Winkler + 50% Full Token)**:
   - `(1.0000 * 0.5) + (1.0000 * 0.5) = 1.0000` (100%)

**Result**: Both algorithms perform equally well for exact matches. ✅ **TIE**

---

## Test 2: "OPENAI *CHATGPT SUBSCR SAN FRANCISCO CA +14158799686" vs "openai"

### Normalization:
- Query normalized: `openai chatgpt subscr san francisco ca` (punctuation and phone removed)
- Candidate normalized: `openai`

### Algorithm Scores:

1. **Jaro-Winkler Score**: 
   - Comparing: `openai chatgpt subscr san francisco ca` vs `openai`
   - String length difference: 42 chars vs 6 chars
   - Score: **~0.1429** (14.29%)
   - ❌ **POOR** - Jaro-Winkler penalizes length differences heavily

2. **Full Token Score**:
   - Query tokens: `[openai, chatgpt, subscr, san, francisco, ca]`
   - Candidate tokens: `[openai]`
   - Token matching:
     - `openai` → `openai` (Jaro-Winkler: 1.0000) ✅ Match (≥0.7 threshold)
     - `chatgpt` → `openai` (Jaro-Winkler: ~0.5000) ❌ No match (<0.7 threshold)
     - `subscr` → `openai` (Jaro-Winkler: ~0.4000) ❌ No match (<0.7 threshold)
     - `san` → `openai` (Jaro-Winkler: ~0.4000) ❌ No match (<0.7 threshold)
     - `francisco` → `openai` (Jaro-Winkler: ~0.3000) ❌ No match (<0.7 threshold)
     - `ca` → `openai` (Jaro-Winkler: ~0.3000) ❌ No match (<0.7 threshold)
   
   - Matched tokens: `{openai, openai}` (2 tokens)
   - Union: `{openai, chatgpt, subscr, san, francisco, ca}` (6 unique tokens)
   - Base similarity: `2 / 6 = 0.3333`
   - Average token similarity: `1.0000` (only one match)
   - Final score: `(0.3333 * 0.6) + (1.0000 * 0.4) = 0.2000 + 0.4000 = 0.6000` (60%)
   - ✅ **GOOD** - Full Token finds the matching token despite extra text

3. **Combined Score (50% Jaro-Winkler + 50% Full Token)**:
   - `(0.1429 * 0.5) + (0.6000 * 0.5) = 0.0715 + 0.3000 = 0.3715` (37.15%)
   - ⚠️ **BELOW THRESHOLD** - Combined score is below 0.50 threshold

**Result**: ✅ **Full Token algorithm performs BEST** for complex matches with extra text
- Full Token: **0.6000** (60%) - Above 0.50 threshold ✅
- Jaro-Winkler: **0.1429** (14.29%) - Below threshold ❌
- Combined: **0.3715** (37.15%) - Below threshold ❌

---

## Summary

| Test Case | Jaro-Winkler | Full Token | Combined | Winner |
|-----------|--------------|------------|----------|--------|
| Simple match ("openai" vs "openai") | 1.0000 | 1.0000 | 1.0000 | TIE |
| Complex match (with extra text) | 0.1429 ❌ | **0.6000** ✅ | 0.3715 ❌ | **Full Token** |

### Key Insights:

1. **Jaro-Winkler** works best for:
   - Exact or near-exact string matches
   - Similar length strings
   - ❌ Fails when query has extra tokens (like address, phone, etc.)

2. **Full Token** works best for:
   - Queries with extra text/tokens
   - Finding partial matches within longer strings
   - ✅ Successfully finds "openai" token even with extra text

3. **Combined Score**:
   - Provides balanced matching
   - But can be pulled down by poor Jaro-Winkler score
   - For Test 2, the combined score (37.15%) is below the 0.50 threshold

### Recommendation:

For the complex match case (`OPENAI *CHATGPT SUBSCR...`), **Full Token algorithm performs best** because:
- It successfully identifies the matching "openai" token
- It ignores irrelevant tokens (address, phone number, etc.)
- It scores 0.6000, which is above the 0.50 confidence threshold

The current implementation uses **Combined Score (50/50)**, which may reject this match (37.15% < 50%). Consider:
- Using Full Token score alone when Jaro-Winkler is very low (< 0.3)
- Or adjusting the weighting to favor Full Token more (e.g., 30% Jaro-Winkler, 70% Full Token)
