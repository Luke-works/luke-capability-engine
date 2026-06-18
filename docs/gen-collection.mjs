// Generator for the luke-capability-engine Postman collection, targeted at the
// luke-auth-engine GATEWAY (https://authdev.lukeflow.com).
//
// The gateway verifies a WorkOS access token, mints an internal act-as badge,
// and forwards downstream. So every authed request just needs the WorkOS access
// token as Bearer — obtained from POST /auth/login (folder 0). Only the routes
// the core-engine proxy whitelists are reachable through the gateway; the admin
// /api/tenants/** + /api/users/** routes are server-to-server and live in a
// separate "direct-to-engine" folder.
//
// Run: node docs/gen-collection.mjs  → writes Lukeflow-Capability-Engine.postman_collection.json
import { writeFileSync } from "node:fs";

const J = "application/json";

// ── helpers ──────────────────────────────────────────────────────────────
const hdr = (k, v) => ({ key: k, value: v });
const tenantHdr = () => hdr("X-Tenant-Id", "{{tenantId}}");
const jsonHdr = () => hdr("Content-Type", J);

const bearerAuth = () => ({ type: "bearer", bearer: [{ key: "token", value: "{{authToken}}", type: "string" }] });
const basicAuth = () => ({
  type: "basic",
  basic: [
    { key: "username", value: "{{operatorUser}}", type: "string" },
    { key: "password", value: "{{operatorPassword}}", type: "string" },
  ],
});
const noAuth = () => ({ type: "noauth" });

const url = (base, segments, query) => {
  const path = segments.filter((s) => s !== "");
  const raw = `{{${base}}}/` + path.join("/") + (query && query.length ? "?" + query.map((q) => `${q.key}=${q.value}`).join("&") : "");
  const u = { raw, host: [`{{${base}}}`], path };
  if (query && query.length) u.query = query.map((q) => ({ key: q.key, value: q.value, ...(q.disabled ? { disabled: true } : {}) }));
  return u;
};

const test = (lines) => ({ listen: "test", script: { type: "text/javascript", exec: lines } });

const req = ({ name, method, base = "baseUrl", segments, query, headers = [], body, auth, tests, description }) => {
  const r = { name, request: { method, header: headers, url: url(base, segments, query) } };
  if (description) r.request.description = description;
  if (auth) r.request.auth = auth;
  if (body !== undefined) r.request.body = { mode: "raw", raw: typeof body === "string" ? body : JSON.stringify(body, null, 2), options: { raw: { language: "json" } } };
  if (tests) r.event = [test(tests)];
  return r;
};

const ok = (codes = [200]) => `pm.test("status ${codes.join("/")}", () => pm.expect(${JSON.stringify(codes)}).to.include(pm.response.code));`;
const save = (varName, jsonPath) => `try { const j = pm.response.json(); if (j${jsonPath} != null) pm.collectionVariables.set("${varName}", j${jsonPath}); } catch (e) {}`;

// ── 0 · Sign in (gateway auth) ─────────────────────────────────────────────
const folderSignIn = {
  name: "0 · Sign in (gateway)",
  description:
    "Authenticate against the gateway and capture the WorkOS access token used as Bearer by every other request.\n\n" +
    "Run **Login** first: it stores `authToken` (and `tenantId` from your session) automatically. The token is short-lived — re-run **Login** or **Refresh** when calls start returning 401.",
  auth: noAuth(),
  item: [
    req({
      name: "Login (email + password)",
      method: "POST",
      headers: [jsonHdr()],
      segments: ["auth", "login"],
      body: { email: "{{email}}", password: "{{password}}" },
      description: "POST /auth/login → { accessToken, sid, user, session }. Stores accessToken as {{authToken}} and session.tenant as {{tenantId}}. (If your account is SSO-only, use the Lukeflow UI to sign in and copy the Bearer token instead.)",
      tests: [
        ok([200]),
        save("authToken", ".accessToken"),
        save("tenantId", ".session.tenant"),
        'try { const t = pm.response.json().session?.tenants; if (t && t.length && !pm.collectionVariables.get("tenantId")) pm.collectionVariables.set("tenantId", t[0]); } catch (e) {}',
      ],
    }),
    req({
      name: "Refresh (from cookie)",
      method: "POST",
      segments: ["auth", "refresh"],
      description: "POST /auth/refresh → new access token from the HttpOnly refresh cookie set at login. Re-stores {{authToken}}.",
      tests: [ok([200]), save("authToken", ".accessToken")],
    }),
    req({
      name: "Session (who am I / capabilities)",
      method: "GET",
      auth: bearerAuth(),
      headers: [tenantHdr()],
      segments: ["session"],
      description: "GET /session → userId, tenant(s), roles, capabilities. Confirms the token works and shows what the user can do.",
      tests: [ok([200])],
    }),
    req({
      name: "Logout",
      method: "POST",
      auth: bearerAuth(),
      segments: ["auth", "logout"],
      tests: [ok([200, 204])],
    }),
  ],
};

// ── gateway-reachable capability routes (Bearer {{authToken}}) ─────────────
const folderCatalog = {
  name: "1 · Capability catalog",
  description: "Platform-wide capability catalog, addressed by stable business code (FORMS, CALENDAR, …). Proxied through the gateway; writes may be operator-gated upstream.",
  item: [
    req({ name: "List capabilities", method: "GET", segments: ["api", "capabilities"], query: [{ key: "status", value: "ACTIVE", disabled: true }], tests: [ok([200])] }),
    req({ name: "Get capability by code", method: "GET", segments: ["api", "capabilities", "{{capabilityCode}}"], tests: [ok([200])] }),
  ],
};

const folderMine = {
  name: "2 · My access (tenant + user)",
  description: "Caller-scoped reads. Identity comes from the Bearer token (the gateway mints the act-as badge); the active tenant comes from X-Tenant-Id.",
  item: [
    req({ name: "My subscriptions", method: "GET", headers: [tenantHdr()], segments: ["api", "my-subscriptions"], tests: [ok([200])] }),
    req({ name: "My capabilities", method: "GET", headers: [tenantHdr()], segments: ["api", "my-capabilities"], tests: [ok([200])] }),
  ],
};

const folderFormDefs = {
  name: "3 · Form definitions",
  description: "Authoring, versioning (check-in → publish), edit-lock, clone, lifecycle. Tenant-scoped via X-Tenant-Id; gated by the FORMS capability (GET=read, mutations=read-write). Run 'Create form' first — it stores {{formId}} and {{formCode}}.",
  item: [
    req({ name: "Create form", method: "POST", headers: [tenantHdr(), jsonHdr()], segments: ["api", "form-definitions"], body: { name: "Contact request", description: "Demo form created from Postman" }, tests: [ok([201]), save("formId", ".id"), save("formCode", ".code")] }),
    req({ name: "List forms", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions"], query: [{ key: "status", value: "PUBLISHED", disabled: true }, { key: "deleted", value: "false", disabled: true }], tests: [ok([200])] }),
    req({ name: "Get form by id", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}"], tests: [ok([200])] }),
    req({ name: "Get form by code", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions", "by-code", "{{formCode}}"], tests: [ok([200])] }),
    req({ name: "Patch form metadata", method: "PATCH", headers: [tenantHdr(), jsonHdr()], segments: ["api", "form-definitions", "{{formId}}"], body: { name: "Contact request (v2)", description: "Updated description" }, tests: [ok([200])] }),
    req({ name: "Save draft schema", method: "PUT", headers: [tenantHdr(), jsonHdr()], segments: ["api", "form-definitions", "{{formId}}", "draft"], body: { schema: "{\"fields\":[{\"key\":\"email\",\"type\":\"text\",\"label\":\"Email\",\"required\":true}]}" }, tests: [ok([200])] }),
    req({ name: "Check in version (auto-publishes first)", method: "POST", headers: [tenantHdr(), jsonHdr()], segments: ["api", "form-definitions", "{{formId}}", "versions"], body: { publish: true }, description: "Compiles the draft into a new immutable version. First check-in auto-publishes; pass publish=true to publish later ones. Schema is optional (falls back to the stored draft).", tests: [ok([201])] }),
    req({ name: "List versions", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "versions"], tests: [ok([200])] }),
    req({ name: "Get version v1", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "versions", "1"], tests: [ok([200])] }),
    req({ name: "Publish version v1", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "versions", "1", "publish"], tests: [ok([200])] }),
    req({ name: "Restore version v1 to draft", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "versions", "1", "restore"], tests: [ok([200])] }),
    req({ name: "Resolve schema by code", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions", "by-code", "{{formCode}}", "schema"], query: [{ key: "pin", value: "published" }], description: "pin = published (default) | latest | draft | v{n}", tests: [ok([200])] }),
    req({ name: "Resolve fields by code", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions", "by-code", "{{formCode}}", "fields"], query: [{ key: "pin", value: "published" }], tests: [ok([200])] }),
    req({ name: "Get embed token", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "embed-token"], description: "Mints a signed embed token (requires a published version). Stored as {{embedToken}} for the Public embed folder.", tests: [ok([200]), save("embedToken", ".token")] }),
    req({ name: "Checkout (acquire edit lock)", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "checkout"], query: [{ key: "force", value: "false" }], tests: [ok([200])] }),
    req({ name: "Release edit lock", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "release"], query: [{ key: "force", value: "false" }], tests: [ok([200])] }),
    req({ name: "Discard draft", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "discard"], tests: [ok([200])] }),
    req({ name: "Clone form", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "clone"], tests: [ok([201])] }),
    req({ name: "Audit trail", method: "GET", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "audit"], tests: [ok([200])] }),
    req({ name: "Retire form", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "retire"], tests: [ok([200])] }),
    req({ name: "Unretire form", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "unretire"], tests: [ok([200])] }),
    req({ name: "Soft delete (to trash)", method: "DELETE", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}"], tests: [ok([204])] }),
    req({ name: "Restore from trash", method: "POST", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "restore"], tests: [ok([200])] }),
    req({ name: "Purge (hard delete) — destructive", method: "DELETE", headers: [tenantHdr()], segments: ["api", "form-definitions", "{{formId}}", "purge"], tests: [ok([204])] }),
  ],
};

const folderInstances = {
  name: "4 · Form instances",
  description: "Runtime occurrences of a published form version. Tenant-scoped via X-Tenant-Id. States: CREATED→SENT→OPENED→IN_PROGRESS→SUBMITTED→PROCESSED (plus EXPIRED, CANCELLED). 'Create instance' stores {{instanceId}} and {{instanceToken}}.",
  item: [
    req({ name: "Create instance", method: "POST", headers: [tenantHdr(), jsonHdr()], segments: ["api", "form-instances"], body: { definitionCode: "{{formCode}}", prefill: { email: "demo@example.com" }, recipient: { email: "demo@example.com", name: "Demo" }, context: { source: "postman" } }, description: "definitionCode is required. version defaults to the form's published version. expiresAt is epoch millis.", tests: [ok([201]), save("instanceId", ".instance.id"), save("instanceToken", ".instance.token")] }),
    req({ name: "List instances", method: "GET", headers: [tenantHdr()], segments: ["api", "form-instances"], query: [{ key: "state", value: "SUBMITTED", disabled: true }, { key: "definitionCode", value: "{{formCode}}", disabled: true }], tests: [ok([200])] }),
    req({ name: "Get instance by id", method: "GET", headers: [tenantHdr()], segments: ["api", "form-instances", "{{instanceId}}"], tests: [ok([200])] }),
    req({ name: "Get instance by token", method: "GET", headers: [tenantHdr()], segments: ["api", "form-instances", "by-token", "{{instanceToken}}"], tests: [ok([200])] }),
    req({ name: "Save (autosave) instance data", method: "PATCH", headers: [tenantHdr(), jsonHdr()], segments: ["api", "form-instances", "{{instanceId}}"], body: { data: { email: "updated@example.com" } }, tests: [ok([200])] }),
    req({ name: "Submit instance", method: "POST", headers: [tenantHdr(), jsonHdr()], segments: ["api", "form-instances", "{{instanceId}}", "submit"], body: { data: { email: "final@example.com" } }, tests: [ok([200])] }),
    req({ name: "Retry process start", method: "POST", headers: [tenantHdr()], segments: ["api", "form-instances", "{{instanceId}}", "retry-process"], tests: [ok([200])] }),
    req({ name: "Set state", method: "PUT", headers: [tenantHdr(), jsonHdr()], segments: ["api", "form-instances", "{{instanceId}}", "state"], body: { state: "OPENED", reason: "opened by recipient" }, tests: [ok([200])] }),
    req({ name: "Send instance", method: "POST", headers: [tenantHdr()], segments: ["api", "form-instances", "{{instanceId}}", "send"], tests: [ok([200])] }),
    req({ name: "Mark processed", method: "POST", headers: [tenantHdr()], segments: ["api", "form-instances", "{{instanceId}}", "processed"], tests: [ok([200])] }),
    req({ name: "Cancel instance", method: "POST", headers: [tenantHdr()], segments: ["api", "form-instances", "{{instanceId}}", "cancel"], tests: [ok([200])] }),
  ],
};

const folderEmbed = {
  name: "5 · Public embed (unauthenticated)",
  description: "The public iframe + inbound-webhook surface — the only path that needs no gateway token. The signed embed token in the path IS the auth. Get one from '3 · Form definitions → Get embed token' (stored as {{embedToken}}).",
  auth: noAuth(),
  item: [
    req({ name: "Render published form", method: "GET", segments: ["api", "public", "embed", "{{embedToken}}"], tests: [ok([200])] }),
    req({ name: "Submit (inbound webhook)", method: "POST", headers: [jsonHdr()], segments: ["api", "public", "embed", "{{embedToken}}", "submit"], body: { data: { email: "public@example.com" } }, description: "Records a SUBMITTED instance and best-effort starts the intake process. Rate-limited to 20/min per token.", tests: [ok([200])] }),
  ],
};

const folderHealth = {
  name: "6 · Health",
  description: "Gateway actuator health.",
  auth: noAuth(),
  item: [req({ name: "Health", method: "GET", segments: ["actuator", "health"], tests: [ok([200])] })],
};

// ── direct-to-engine admin (NOT via the gateway) ───────────────────────────
const folderAdminDirect = {
  name: "7 · Admin — direct to engine (NOT via gateway)",
  description:
    "⚠️ These privileged routes are NOT exposed through https://authdev.lukeflow.com — the gateway/core-engine proxy does not forward /api/tenants/** or /api/users/**, and the gateway overwrites Authorization so Basic auth can't pass through.\n\n" +
    "They are server-to-server (core-engine → capability-engine). To exercise them you must hit the capability-engine instance DIRECTLY using {{engineDirectUrl}} with the operator HTTP Basic credential ({{operatorUser}}/{{operatorPassword}}). Set those variables first.",
  auth: basicAuth(),
  item: [
    req({ name: "Subscribe tenant to capability", method: "PUT", base: "engineDirectUrl", segments: ["api", "tenants", "{{tenantId}}", "capabilities", "{{capabilityCode}}"], tests: [ok([200])] }),
    req({ name: "Disable capability for tenant", method: "DELETE", base: "engineDirectUrl", segments: ["api", "tenants", "{{tenantId}}", "capabilities", "{{capabilityCode}}"], query: [{ key: "hard", value: "false" }], tests: [ok([204])] }),
    req({ name: "List subscriptions for tenant", method: "GET", base: "engineDirectUrl", segments: ["api", "tenants", "{{tenantId}}", "capabilities"], tests: [ok([200])] }),
    req({ name: "Grant user a capability level", method: "PUT", base: "engineDirectUrl", headers: [jsonHdr()], segments: ["api", "tenants", "{{tenantId}}", "users", "{{userId}}", "capabilities", "{{capabilityCode}}"], body: { level: "read-write" }, description: "level must be 'read' or 'read-write'. 409 if the tenant is not subscribed to the capability.", tests: [ok([200])] }),
    req({ name: "List grants for user", method: "GET", base: "engineDirectUrl", segments: ["api", "tenants", "{{tenantId}}", "users", "{{userId}}", "capabilities"], tests: [ok([200])] }),
    req({ name: "Revoke user grant", method: "DELETE", base: "engineDirectUrl", segments: ["api", "tenants", "{{tenantId}}", "users", "{{userId}}", "capabilities", "{{capabilityCode}}"], tests: [ok([204])] }),
    req({ name: "Purge tenant (cleanup cascade)", method: "DELETE", base: "engineDirectUrl", segments: ["api", "tenants", "{{tenantId}}"], tests: [ok([204])] }),
    req({ name: "Purge user (cleanup cascade)", method: "DELETE", base: "engineDirectUrl", auth: noAuth(), segments: ["api", "users", "{{userId}}"], description: "Under /api/users/** — not behind the operator filter (which scopes to /api/tenants/**).", tests: [ok([204])] }),
  ],
};

// ── collection ─────────────────────────────────────────────────────────────
const collection = {
  info: {
    name: "Lukeflow — Capability Engine API (via authdev gateway)",
    description:
      "Capability-engine API reached through the **luke-auth-engine gateway** at **https://authdev.lukeflow.com**.\n\n" +
      "## Getting a token\n" +
      "Run **`0 · Sign in → Login`** (POST `/auth/login` with `email`/`password`). It returns `{ accessToken, session }` and the test script stores `accessToken` as the `authToken` collection variable + `session.tenant` as `tenantId`. Every other request sends `Authorization: Bearer {{authToken}}` (collection-level auth). The gateway verifies that token and mints its own internal act-as badge — you never handle that badge.\n\n" +
      "If your account is SSO-only (`/auth/login` returns `sso_required`), sign in via the Lukeflow UI and copy the `Authorization: Bearer …` value from a network request into `authToken`.\n\n" +
      "Tokens are short-lived — re-run **Login** or **Refresh** on a 401.\n\n" +
      "## Scope\n" +
      "Folders 1–6 are the routes the gateway actually proxies (catalog, my-access, form definitions, form instances, public embed, health). The active tenant is sent via `X-Tenant-Id`; the user identity comes from the token, so no `X-User-Id` is needed.\n\n" +
      "Folder 7 holds the privileged admin routes (`/api/tenants/**`, `/api/users/**`) which are **NOT** reachable through the gateway — they require hitting capability-engine directly with the operator Basic credential. See that folder's notes.",
    schema: "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
  },
  auth: bearerAuth(),
  item: [folderSignIn, folderCatalog, folderMine, folderFormDefs, folderInstances, folderEmbed, folderHealth, folderAdminDirect],
  variable: [
    { key: "baseUrl", value: "https://authdev.lukeflow.com", type: "string" },
    { key: "email", value: "gowthamrajum+lukeatwork@gmail.com", type: "string" },
    { key: "password", value: "LukeAtWork-106466!Aa", type: "string" },
    { key: "authToken", value: "", type: "string" },
    { key: "tenantId", value: "TEN-IYQ-14JUN26", type: "string" },
    { key: "capabilityCode", value: "FORMS", type: "string" },
    { key: "formId", value: "", type: "string" },
    { key: "formCode", value: "", type: "string" },
    { key: "instanceId", value: "", type: "string" },
    { key: "instanceToken", value: "", type: "string" },
    { key: "embedToken", value: "", type: "string" },
    // ── only for folder 7 (direct-to-engine admin) ──
    { key: "engineDirectUrl", value: "http://localhost:8082", type: "string" },
    { key: "operatorUser", value: "", type: "string" },
    { key: "operatorPassword", value: "", type: "string" },
    { key: "userId", value: "workos:user_01KV2FCQEW1NVPW41AYT64MTGK", type: "string" },
  ],
};

writeFileSync(new URL("./Lukeflow-Capability-Engine.postman_collection.json", import.meta.url), JSON.stringify(collection, null, 2) + "\n");
console.log("wrote Lukeflow-Capability-Engine.postman_collection.json");
