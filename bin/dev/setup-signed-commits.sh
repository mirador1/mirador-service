#!/usr/bin/env bash
# =============================================================================
# bin/dev/setup-signed-commits.sh — interactive helper for SSH-signed commits.
#
# Per the global CLAUDE.md "NEVER update the git config" safety rule, Claude
# itself can't run `git config --global`. This script lets the USER run those
# commands explicitly with a preview + confirmation step. Same outcome,
# auditable trail.
#
# What it does:
#   1. Detects existing signing config (no-op if already set up correctly)
#   2. Picks the SSH key (auto-detects ~/.ssh/id_ed25519.pub by default)
#   3. Shows EXACTLY what `git config --global` calls will run
#   4. Prompts for confirmation
#   5. Optional: ensures the key is registered as a SIGNING key on GitHub
#      via `gh ssh-key add --type signing`
#   6. Optional: configures `--global commit.gpgsign true` (you're committing
#      to sign every commit going forward — easy to revert later via
#      `git config --global --unset commit.gpgsign`)
#
# Why SSH and not GPG: GitHub + GitLab both accept SSH keys for signing
# (GitHub since 2022, GitLab since 14.5). No GPG keyring management,
# no expired-subkey hassles, reuses your existing SSH identity.
#
# Run:    bin/dev/setup-signed-commits.sh
# Dry-run: bin/dev/setup-signed-commits.sh --dry-run
# =============================================================================
set -euo pipefail

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

# ── Helpers ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[0;33m'
BLU='\033[0;34m'
NC='\033[0m'

note() { echo -e "${BLU}▸${NC} $*"; }
ok()   { echo -e "${GRN}✓${NC} $*"; }
warn() { echo -e "${YLW}!${NC} $*"; }
err()  { echo -e "${RED}✗${NC} $*" >&2; }

run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    echo -e "  ${YLW}DRY-RUN${NC} would run: $*"
  else
    eval "$@"
  fi
}

# ── Step 0 — Pre-flight ──────────────────────────────────────────────────────
note "Pre-flight check"

if [[ "$(uname)" != "Darwin" && "$(uname)" != "Linux" ]]; then
  err "Only macOS + Linux supported (got: $(uname))"
  exit 1
fi

if ! command -v ssh-keygen >/dev/null; then
  err "ssh-keygen not found — install OpenSSH first"
  exit 1
fi

git_user_name=$(git config --global user.name || echo "")
git_user_email=$(git config --global user.email || echo "")
if [[ -z "$git_user_name" || -z "$git_user_email" ]]; then
  err "Set git user.name + user.email first:"
  echo "    git config --global user.name 'Your Name'"
  echo "    git config --global user.email 'you@example.com'"
  exit 1
fi
ok "git identity: $git_user_name <$git_user_email>"

# ── Step 1 — Detect existing signing config ──────────────────────────────────
note "Existing signing config"

existing_format=$(git config --global gpg.format || echo "")
existing_signingkey=$(git config --global user.signingkey || echo "")
existing_gpgsign=$(git config --global commit.gpgsign || echo "false")

if [[ "$existing_format" == "ssh" && -n "$existing_signingkey" ]]; then
  ok "SSH signing already configured"
  echo "    gpg.format        = $existing_format"
  echo "    user.signingkey   = $existing_signingkey"
  echo "    commit.gpgsign    = $existing_gpgsign"
  echo
  read -r -p "Reconfigure anyway? [y/N] " ans
  [[ "$ans" =~ ^[Yy] ]] || { ok "Nothing to do."; exit 0; }
fi

# ── Step 2 — Pick the SSH key ────────────────────────────────────────────────
note "SSH key selection"

CANDIDATE_KEYS=()
for k in "$HOME/.ssh/id_ed25519.pub" "$HOME/.ssh/id_rsa.pub" "$HOME/.ssh/id_ecdsa.pub"; do
  [[ -f "$k" ]] && CANDIDATE_KEYS+=("$k")
done

if [[ ${#CANDIDATE_KEYS[@]} -eq 0 ]]; then
  err "No SSH public key found in ~/.ssh/"
  echo "    Generate one with: ssh-keygen -t ed25519 -C \"$git_user_email\""
  exit 1
fi

if [[ ${#CANDIDATE_KEYS[@]} -eq 1 ]]; then
  SSH_PUB_KEY="${CANDIDATE_KEYS[0]}"
  ok "Using the only candidate: $SSH_PUB_KEY"
else
  echo "Multiple SSH public keys found:"
  for i in "${!CANDIDATE_KEYS[@]}"; do
    echo "    [$i] ${CANDIDATE_KEYS[$i]}"
  done
  read -r -p "Pick index: " idx
  SSH_PUB_KEY="${CANDIDATE_KEYS[$idx]}"
  ok "Picked: $SSH_PUB_KEY"
fi

# ── Step 3 — Preview the git config commands ────────────────────────────────
note "Commands to run (git config — global)"

cat <<EOF
    git config --global gpg.format ssh
    git config --global user.signingkey "$SSH_PUB_KEY"
    git config --global commit.gpgsign true
    git config --global gpg.ssh.allowedSignersFile "$HOME/.ssh/allowed_signers"
EOF

# ── Step 4 — Confirm ─────────────────────────────────────────────────────────
echo
read -r -p "Apply these git config --global changes? [y/N] " ans
if [[ ! "$ans" =~ ^[Yy] ]]; then
  ok "Aborted, nothing changed."
  exit 0
fi

# ── Step 5 — Apply (or dry-run) ──────────────────────────────────────────────
note "Applying"

run git config --global gpg.format ssh
run git config --global user.signingkey "\"$SSH_PUB_KEY\""
run git config --global commit.gpgsign true
run git config --global gpg.ssh.allowedSignersFile "\"$HOME/.ssh/allowed_signers\""

# allowed_signers file lets `git log --show-signature` verify your own
# past commits locally. Format: "email key-type key-data"
ALLOWED_SIGNERS_LINE="$git_user_email $(awk '{print $1, $2}' "$SSH_PUB_KEY")"
if [[ ! -f "$HOME/.ssh/allowed_signers" ]]; then
  note "Creating ~/.ssh/allowed_signers"
  if [[ $DRY_RUN -eq 1 ]]; then
    echo -e "  ${YLW}DRY-RUN${NC} would write: $ALLOWED_SIGNERS_LINE"
  else
    echo "$ALLOWED_SIGNERS_LINE" > "$HOME/.ssh/allowed_signers"
    chmod 600 "$HOME/.ssh/allowed_signers"
  fi
elif ! grep -qF "$git_user_email" "$HOME/.ssh/allowed_signers"; then
  note "Appending your identity to ~/.ssh/allowed_signers"
  if [[ $DRY_RUN -eq 1 ]]; then
    echo -e "  ${YLW}DRY-RUN${NC} would append: $ALLOWED_SIGNERS_LINE"
  else
    echo "$ALLOWED_SIGNERS_LINE" >> "$HOME/.ssh/allowed_signers"
  fi
fi

# ── Step 6 — Optional: register the SIGNING key on GitHub ───────────────────
note "GitHub signing-key registration"

if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  if gh ssh-key list --json type 2>/dev/null | grep -q '"signing_key"'; then
    ok "Already have a signing-type SSH key on GitHub"
  else
    echo "  Will run: gh ssh-key add \"$SSH_PUB_KEY\" --type signing --title \"signing key (\$(hostname))\""
    read -r -p "  Add this SSH key as a SIGNING key on your GitHub account? [y/N] " ans
    if [[ "$ans" =~ ^[Yy] ]]; then
      run gh ssh-key add "\"$SSH_PUB_KEY\"" --type signing --title "\"signing key (\$(hostname -s))\""
      ok "Key registered on GitHub"
    fi
  fi
else
  warn "gh not authenticated — skipping GitHub signing-key registration"
  echo "    Manual: https://github.com/settings/ssh/new (choose 'Signing Key')"
fi

# ── Step 7 — Verify ──────────────────────────────────────────────────────────
note "Verification"

if [[ $DRY_RUN -eq 1 ]]; then
  ok "Dry-run complete, no changes made."
  exit 0
fi

CURRENT=$(git config --global gpg.format)
[[ "$CURRENT" == "ssh" ]] && ok "gpg.format=ssh" || warn "gpg.format=$CURRENT (expected ssh)"

CURRENT=$(git config --global user.signingkey)
[[ "$CURRENT" == "$SSH_PUB_KEY" ]] && ok "user.signingkey set" || warn "user.signingkey mismatch"

CURRENT=$(git config --global commit.gpgsign)
[[ "$CURRENT" == "true" ]] && ok "commit.gpgsign=true" || warn "commit.gpgsign=$CURRENT"

echo
note "Next steps"
echo "  1. Make a test commit anywhere: git commit --allow-empty -m 'test signing'"
echo "  2. Verify: git log --show-signature -1  → should print 'Good \"git\" signature'"
echo "  3. Push, then check the commit on GitHub/GitLab — should display a 'Verified' badge"
echo "  4. Re-enable required_signatures on protected branches:"
echo "     - GitHub: Settings → Branches → Edit main rule → check 'Require signed commits'"
echo "     - Or via API: gh api repos/<owner>/<repo>/branches/main/protection \\"
echo "         --method PUT --raw-field required_signatures.enabled=true"
echo "  5. To rollback per-commit signing: git commit --no-gpg-sign"
echo "  6. To rollback globally:           git config --global --unset commit.gpgsign"
