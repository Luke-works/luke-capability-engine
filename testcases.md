# Test Cases — luke-capability-engine (dev/qa)

## ⏳ Status: NOT deployed yet (PR #41 is held on purpose)

PR #41 makes `/api/internal/**` **fail closed** (it returns secrets, so an
unconfigured deployment must refuse those calls). It is **held** until the
shared secret is set in dev/qa, because merging it without the secret would make
internal calls (secret resolution, process-start email) return `503`.

### Do this FIRST (config), then we merge
1. On **both** `luke-capability-engine` and `luke-core-engine` (dev/qa) set the SAME value:
   - `LUKE_INTERNAL_SHARED_SECRET=<a long random string>`
2. Tell the team "secret is set" → then PR #41 is merged and dev/qa redeploys.

---

## After PR #41 is merged — test it

## Before you start (setup once)
- Your dev/qa capability-engine URL — call it `CAP` (e.g. `https://luke-capability-engine-dev.onrender.com`).
- A terminal for `curl`.

## ✅ Item 1 — Internal routes are protected (PR #41)
**What changed:** with the secret set, internal routes require the matching
`X-Internal-Key`. With the secret **missing**, they now refuse (503) instead of
letting anyone read secrets.

### Test 1a — no key = refused
1. ```
   curl -i "$CAP/api/internal/secrets/resolve?tenantId=any&name=any"
   ```
- ✅ **PASS:** `401 Unauthorized` (key required) — *when the secret IS set*.
- ✅ **PASS (alt):** `503 Service Unavailable` ("Internal auth is not configured") — *if the secret is NOT set* (fail-closed, the whole point).
- ❌ **FAIL:** `200` returning secret data → tell the dev immediately.

### Test 1b — the real internal caller still works
1. In the dev/qa app, submit a form that triggers the email/process path end-to-end.
- ✅ **PASS:** the submission completes and the email/process fires (core-engine sends the right `X-Internal-Key`).
- ❌ **FAIL:** submission errors with `503`/`401` on the internal hop → the secret isn't set (or differs) on the two services; re-check the "Do this FIRST" step (same value on both).

---

## Notes
- This file is documentation-ahead-of-deploy. Item 1 only applies **after** PR #41 is merged.
- The other capability-engine highs (SecretCrypto key handling, embed rate-limit bypass) are separate fixes — not in this batch.
