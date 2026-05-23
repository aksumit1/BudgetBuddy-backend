# Per-user Fine-tune via Custom Mappings — Design

## Problem

The category cascade has a per-user layer (L0 `CustomMerchantMappingTable`) that holds explicit user overrides. But the override is exact-match only: a user who labels `Trader Joe's #321` as `groceries` doesn't automatically get `Trader Joe's #418` (a different store number) labeled the same way. Each variant requires a separate explicit override.

Worse, user corrections carry implicit patterns the deterministic cascade can't capture:
- "When I shop at Whole Foods before 11am, that's groceries; after 6pm, it's a corporate meal reimbursement"
- "$22-$30 charges at 'Eats' are work lunches; >$30 are personal dining"

These patterns can't be encoded as substring rules without becoming brittle. They are exactly the kind of thing a small model can learn from a few hundred examples.

## Proposal

Train a per-user LoRA adapter on the base classification model nightly. The adapter sits on top of a shared base classifier (single weights, ~100MB) and adds 2–5MB per user. The training data is the user's own `CustomMerchantMappingTable` entries plus their accepted L8/L9 categorizations.

### Data flow

```
nightly:
  for each user with >= 50 distinct corrections:
    fetch CustomMerchantMappingTable rows + accepted L8/L9 categorizations
    construct (merchant, description, amount, time, account_type) -> category pairs
    train LoRA adapter on this user's data for 1-2 epochs
    persist adapter to S3: s3://budgetbuddy-user-models/<user-id>/adapter.bin
    update DynamoDB: UserModelTable -> { userId, adapterPath, version, trainedAt }

at inference (per transaction):
  if (userId has an adapter):
    load adapter (LRU-cached)
    score the transaction
    if confidence >= threshold:
      return adapter's prediction with source="USER_MODEL"
    else fall through to cascade
```

### Why LoRA, not full fine-tune

- 100× cheaper to train per user (~$0.20/user/night vs $20)
- Adapters are tiny (2-5MB) — can hold thousands in memory cache
- Base model weights stay frozen — no catastrophic forgetting between users
- LoRA outputs can be merged into the base for export if a power user wants it

### Privacy

This is the load-bearing point. Each user's adapter is trained ONLY on that user's data. The base model is generic. There is no cross-user feature leakage. Adapters live in per-user-prefixed S3 paths under user-scoped KMS keys.

## What we're NOT building

- Per-user FULL model (too expensive, no privacy story)
- Federated learning across users (the whole point is per-user signal, not federated averaging)
- Auto-applied L0 overrides from adapter predictions (the adapter is a signal in the cascade, not a write to the override table — those should stay explicit)

## Infrastructure requirements

1. **Training:** AWS Batch / SageMaker training job, scheduled nightly. Likely needs a GPU node (T4 is plenty for LoRA).
2. **Model storage:** S3 with KMS, per-user prefix.
3. **Inference cache:** in-process LRU of recently-used adapters; size cap by memory.
4. **Inference path:** new `UserModelService` that sits in the cascade between L0 (explicit override) and L2 (MCC). Returns null if no adapter or below threshold.
5. **Versioning:** `UserModelTable` records every adapter version so we can roll back.
6. **Cost:** ~$10/1K active users/month for nightly training; inference adds ~5ms per categorization.

## Why this can't ship today

- No ML training infra in the repo (no Batch/SageMaker integration)
- No GPU access in the production AWS account
- No PII/training-data governance review yet
- No measurement framework — we'd need to A/B per-user-adapter vs baseline cascade and that requires the metric pipeline

## Smallest first step

If we want to validate the premise before building infrastructure: take a sample of 10 power users with > 100 corrections each, dump their data offline, train per-user LoRA adapters with a one-shot script, measure F1 against held-out corrections. If we see ≥10% F1 lift over the cascade baseline, build the infra. If not, this is the wrong investment.

## Status

Design only — implementation requires the infra called out above.
