# Plaid Sandbox Account ID Behavior

## Quick Answer

**No, Plaid account IDs do NOT change on every request in sandbox.**

They are **generally stable** and remain consistent across requests as long as:
- The same `access_token` is used
- The same Plaid Item is used
- Account details don't change significantly

## Detailed Behavior

### Stable Account IDs

Plaid account IDs in sandbox are designed to be **persistent** and **consistent**:

✅ **Same access_token** → Same account IDs  
✅ **Same Item** → Same account IDs  
✅ **Multiple syncs** → Same account IDs (if nothing changed)

### When Account IDs Change

Account IDs **can change** in these scenarios:

1. **Access Token Regeneration** ⚠️ **Most Common Cause**
   - If you delete the access token and create a new one
   - Even with the same bank credentials
   - Plaid assigns **new account IDs**
   - This is why you might see duplicates!

2. **Account Name Changes**
   - If the account name changes significantly
   - And Plaid cannot reconcile it with existing data
   - A new account ID may be assigned

3. **Multiple Link Sessions**
   - If the same bank account is linked multiple times
   - Each link session may get different account IDs
   - Especially if done through different Plaid Link flows

## Implications for Duplicate Detection

### Why You Might See Duplicates

If you have 24 accounts but should only have 12, it's likely because:

1. **Access Token Regenerated**: 
   - User reconnected their bank account
   - New access token was created
   - Plaid assigned new account IDs
   - Old accounts still exist in database
   - Result: Duplicates with different `plaidAccountId` values

2. **Multiple Syncs with Different Tokens**:
   - Each sync created accounts with different Plaid IDs
   - Our deduplication only checks `plaidAccountId`
   - If IDs are different, duplicates are created

### Our Deduplication Strategy

We use **multiple layers** of deduplication:

1. **Primary**: `plaidAccountId` (Plaid's account ID)
2. **Secondary**: `accountNumber + institutionName` (fallback when Plaid ID changes)
3. **Final Check**: Comprehensive scan before creating new accounts

This handles cases where:
- Plaid account IDs change due to token regeneration
- Plaid account IDs are missing
- Account numbers are more stable than Plaid IDs

## Best Practices

### To Avoid Duplicates

1. **Preserve Access Tokens**: Don't delete and regenerate unless necessary
2. **Use Item ID**: Track accounts by `plaidItemId` as well
3. **Use Persistent Account ID**: Consider using `persistent_account_id` for depository accounts
4. **Comprehensive Deduplication**: Always check multiple fields (account number, institution, etc.)

### To Handle Existing Duplicates

1. **Run Analysis Script**: `./scripts/analyze-duplicates-simple.sh`
2. **Identify Duplicate Groups**: Look for accounts with same `accountNumber + institutionName`
3. **Keep Oldest**: The cleanup script keeps the oldest account (by `createdAt`)
4. **Update Missing IDs**: Update accounts with missing `plaidAccountId` when found

## Testing in Sandbox

When testing in Plaid sandbox:

- ✅ Account IDs remain stable across multiple syncs
- ✅ Same test credentials → Same account IDs
- ⚠️ Regenerating access token → New account IDs
- ⚠️ Multiple link sessions → May get different IDs

## Summary

| Scenario | Account ID Changes? |
|----------|-------------------|
| Same access_token, multiple syncs | ❌ No - Stable |
| Same Item, multiple syncs | ❌ No - Stable |
| Regenerated access_token | ✅ Yes - New IDs |
| Account name changed significantly | ✅ Yes - May get new ID |
| Multiple link sessions | ✅ Yes - May get different IDs |

**Bottom Line**: Account IDs are stable in sandbox, but can change when access tokens are regenerated or accounts are re-linked. This is why we need comprehensive deduplication beyond just `plaidAccountId`.

