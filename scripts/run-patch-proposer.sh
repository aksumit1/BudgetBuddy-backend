#!/usr/bin/env bash
#
# Batch runner for the PDF parser patch proposer (Layer 3).
#
# Walks a directory of diagnostic JSON blobs (typically the synced-down copy
# of s3://$PDF_DIAGNOSTICS_BUCKET) and runs each through the LLM proposer.
# For each blob it produces a sibling `*.patch_proposal.md` file. The runner
# then groups proposals by (institution, strategy) so we open at most ONE PR
# per (institution, strategy) per night — preventing PR spam when a single
# parser bug causes a fan-out of failed parses.
#
# Required env:
#   ANTHROPIC_API_KEY     — Anthropic Messages API key
#   PDF_DIAGNOSTICS_DIR   — local directory of diagnostic blobs (defaults to /tmp/pdf-diagnostics)
#
# Optional env:
#   ANTHROPIC_MODEL       — defaults to claude-opus-4-5
#   GH_PR_BRANCH_PREFIX   — defaults to pdf-import/auto-patch
#   GH_REPO               — slug for `gh pr create` (e.g. anthropic/budgetbuddy)
#   DRY_RUN=1             — print prompts; don't call the API; don't open PRs
#
# Idempotency: each diagnostic blob writes a sibling `.patch_proposal.md`. If
# the file already exists, we skip — re-running the script does no harm.

set -euo pipefail

DIR="${PDF_DIAGNOSTICS_DIR:-/tmp/pdf-diagnostics}"
DRY_RUN="${DRY_RUN:-0}"
PROPOSER_CLASS="com.budgetbuddy.tools.PdfPatchProposer"

if [[ ! -d "$DIR" ]]; then
  echo "Diagnostic directory not found: $DIR" >&2
  exit 1
fi

shopt -s globstar nullglob
mapfile -t blobs < <(find "$DIR" -type f -name '*.json' | sort)

if [[ ${#blobs[@]} -eq 0 ]]; then
  echo "No diagnostic blobs in $DIR — nothing to do."
  exit 0
fi

echo "Found ${#blobs[@]} diagnostic blob(s)."
processed=0
skipped=0
errors=0

for blob in "${blobs[@]}"; do
  proposal="${blob%.json}.patch_proposal.md"
  if [[ -f "$proposal" ]]; then
    skipped=$((skipped + 1))
    continue
  fi

  echo
  echo "--- Processing $blob ---"
  if [[ "$DRY_RUN" == "1" ]]; then
    if ! mvn -q exec:java \
        -Dexec.mainClass="$PROPOSER_CLASS" \
        -Dexec.args="--dry-run $blob"; then
      errors=$((errors + 1))
      continue
    fi
  else
    if ! mvn -q exec:java \
        -Dexec.mainClass="$PROPOSER_CLASS" \
        -Dexec.args="$blob"; then
      errors=$((errors + 1))
      continue
    fi
  fi
  processed=$((processed + 1))
done

echo
echo "Done. processed=$processed skipped=$skipped errors=$errors"

# Optionally bundle proposals into branches + PRs. Disabled by default — wire
# this in once you've reviewed a few proposals manually to know the format
# you want.
#
# if [[ -n "${GH_REPO:-}" && "$DRY_RUN" != "1" ]]; then
#   for proposal in "$DIR"/**/*.patch_proposal.md; do
#     branch="${GH_PR_BRANCH_PREFIX:-pdf-import/auto-patch}/$(basename "$proposal" .patch_proposal.md)"
#     git checkout -b "$branch"
#     # Apply the patch from the LLM output (manual step today).
#     git add "$proposal"
#     git commit -m "[pdf-import] auto patch proposal: $branch"
#     git push -u origin "$branch"
#     gh pr create --repo "$GH_REPO" --title "PDF parser patch: $branch" \
#         --body-file "$proposal"
#   done
# fi
