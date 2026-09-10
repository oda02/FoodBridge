# Independent security review

Reviewed 2026-09-11 by a separate agent that did not implement the Android app or website. Current scope: FoodBridge 1.2.0 / versionCode 3 source, payload parsing, operation coordination, static-site configuration, and release-secret handling. Existing unrelated server services are outside this review. This is a scoped code/configuration review, not a claim of complete penetration testing.

## Findings and fixes

### S1 — Medium, closed: recursive regex consumed the JVM stack

The original flat JSON parser used a repeated-alternation regex that produced `StackOverflowError` for a 2,000-character string within the payload byte limit. The URL entry point caught that error; an Activity crash was not demonstrated. The scanner is now iterative and bounded. A direct `parseJson` regression verifies rejection without masking stack errors behind the URL wrapper, and checks the maximum escaped string. Batch parsing also uses bounded iterative token scanning with container depth capped at three.

### S2 — Low, closed: write lock was scoped to an instance

The original coordinator mutex allowed two repository instances to pass the local duplicate check concurrently. The lock is now process-wide and spans permission checking, provider lookup, version comparison, mutation, and history persistence. Independent tests cover separate coordinator instances and interleaved upserts/deletes. The critical section is non-cancellable after acquiring the lock, preventing Activity cancellation from releasing it between a provider effect and the corresponding history/tombstone write. Process death and provider failure remain separate failure modes.

### S3 — Low, closed: browser and Android validation differed

The original browser preview accepted duplicate/unknown fields, null IDs, impossible dates, out-of-range years, and noncanonical schema-version spellings that Android rejected. Strict browser validation and regression tests fixed those cases. Version 1.2 replaces the old singleton/versioned payload with the same `items` envelope used by Android. The current contract and bounds are described below. All DOM rendering remains text-only; the original finding was misleading input acceptance, not a demonstrated XSS path. Independent execution of the final website suite produced **128 passing tests**, including shared Android/browser request vectors and DOM-rendering/privacy regressions.

## Current input and consent boundary

- HTTPS links must use the exact configured host and root path, with no user info, explicit port, query, or custom scheme. Nutrition data is UTF-8 JSON in canonical unpadded base64url after `#`.
- The public schema is one `items` array with 1–20 operations. No `v` field is accepted. Every operation has an explicit serving ID; duplicate IDs within a request are rejected. Only `upsert` and `delete` are supported.
- The request parser bounds bytes, URL length, string/numeric tokens, item count, and JSON nesting. Duplicate decoded keys, unknown fields, malformed UTF-8, invalid Unicode surrogates, control/bidirectional characters, unsupported types, nonfinite nutrition values, and out-of-range nutrition values are rejected.
- Revisions must be exact unquoted integer tokens from 1 through 1,000,000; omission means 1 for an upsert. A delete requires revision 2 or later and only accepts its operation, ID, and revision fields. Nutrition timestamps require valid calendar dates with explicit offsets.
- `MainActivity` is exported for Verified App Links. Domain verification authenticates the destination association, not the source or author of an incoming link. Any app can send an explicit valid Intent. Auto-add is off by default and its settings warning explains this trust boundary.
- Every request containing deletion is excluded from auto-add. The confirmation screen lists its deletion targets, and a deliberate confirmation callback is bound to the exact preview's request ID. The ViewModel rejects stale callbacks after a new link replaces the preview. The coordinator independently requires confirmation before deletion. Unusual nutritional values also require a manual action.
- Batches are sequential operations with individual outcomes. They are not an atomic database transaction. Retrying a partial batch excludes completed items; provider/local revision checks prevent an already completed item being applied again.

## Provider identity, revisions and deletion

FoodBridge requires Android API 34 or newer and requests only `android.permission.health.WRITE_NUTRITION`. It has no `READ_NUTRITION` or network permission. It does inspect its own existing nutrition records through the Android native own-record API; this is a real read of the app's records, not a claim that no reads occur. Platform permission/API behavior is separately reviewed in the Android API report and verified by the device workflow.

The lookup request supplies the deterministic `foodbridge:` plus SHA-256 client record ID. Returned results must contain at most one record, the exact expected client ID, and FoodBridge's package as the data origin. Only the provider record ID from a successfully validated lookup can be deleted. A lookup failure prevents both upsert and delete; it is never treated as evidence that a record is absent.

The maximum of local and provider revisions protects against stale writes/deletes. A matching or older incoming revision is a duplicate and repairs the local cached maximum when necessary. A higher revision updates an existing provider record using its unchanged client identity. If the provider target is absent, an upsert creates it, including when the requested revision is greater than one. Local history is therefore not required to discover an existing original.

A confirmed delete of an absent record returns `ALREADY_ABSENT` and remembers a revision tombstone without issuing a provider delete. A successful delete remembers its tombstone after the provider effect. If deletion throws, only a successful follow-up lookup proving absence permits a completed result; a lookup failure or still-present record remains an error. A retry after a failed local-history write can recover from the now-absent target without deleting twice.

The process-wide mutex covers lookup through persistence. The non-cancellable critical section has an independent cancellation test: cancel an in-flight delete, submit an older upsert on another coordinator, and verify that it waits for deletion/tombstone persistence before returning duplicate. The lock does not make the separate provider/DataStore stores crash-atomic.

History stores at most **1,000 hashed serving IDs with maximum revisions**, including deletion tombstones. Legacy bare hash entries are interpreted as revision 1; updating a row never lowers its revision. Raw IDs cannot inject history delimiters because only their hexadecimal hashes are stored. Android backup is disabled.

**Remaining limitation:** an evicted or cleared deletion tombstone cannot be recovered from a provider record that is absent. Reopening an old upsert after that history loss can recreate a deleted meal. There is no cloud tombstone store. Existing provider records can recover their revisions through own-record lookup, but an absence carries no deletion version.

## Privacy and static hosting

Android logs do not intentionally contain payloads or provider errors. Local persistent application state contains the auto-add preference and bounded ID/revision history, not food names or nutrient payloads. Names read from own records are used in the deletion preview. Health Connect retains the actual nutrition records and controls access by other apps.

Website rendering uses `textContent`, with no analytics, telemetry, third-party scripts/fonts, account, database, or payload-bearing fetch. URL fragments are not part of HTTP request targets. This prevents the server from receiving the fragment, but the complete link remains visible to the chat, browser, and app that hold it.

The dedicated Caddy block serves a static root and does not enable per-site HTTP access logs. It sets a restrictive CSP including `connect-src 'none'`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, frame denial, and disabled camera/microphone/geolocation policy. Independent public checks previously confirmed the actual headers, successful HTTPS, root HTTP 200, and assetlinks HTTP 200 with JSON content type and no redirects/challenges. Effective access logging in the wider server configuration belongs to the deployment verification; no unrelated service configuration was audited here.

## Release signing and secret handling

The stable release certificate SHA-256 is:

`D1:F8:D3:D2:ED:BB:C4:99:00:0F:8A:8F:B9:D2:47:83:C1:0B:1E:67:17:FA:4C:0F:C9:A4:36:92:79:9E:97:0F`

An independent export of the public certificate from the actual private keystore matched the documented fingerprint and Digital Asset Links statement. The statement targets namespace `android_app`, package `ru.kukakur.foodbridge`, with `delegate_permission/common.handle_all_urls`. The **1.2.0 / versionCode 3 APK** was independently verified with `apksigner` and has the same signing certificate.

`private/foodbridge-release.p12` and `signing.properties` are Git-ignored. Their Windows ACLs were inspected and grant FullControl only to the current user. The key-generation script refuses to replace existing signing files. Gradle reads signing secrets from the ignored properties file; no password is embedded in source. Certificate export used a child-process environment and did not print passwords. The latest exact-secret scan covered **57 nonignored files** and found no copies of the real passwords, keystore bytes, or private-key headers; rerun against final tracked files before publication. Public certificate fingerprints are intentionally not secret. The private signing key must be retained for future updates.

## Independent test evidence and limits

- Current coordinator suite: **26 JVM tests passed**, including provider/local version reconciliation, missing targets, permission-before-lookup, no mutation after lookup errors, confirmation, own-record ID selection, provider/history failures, ambiguous deletion replies, partial retry, tombstones, separate-instance races, and cancellation.
- Current site suite: **128 tests passed**. The reviewed code validates the entire request before showing operations, renders HTML-looking names and IDs as literal text, resets state on fragment changes, distinguishes omitted nutrients from zeroes, preserves displayed offsets, and shows manual-deletion/partial-batch warnings without performing network requests or saving data.
- New Compose fixtures cover batch preview, single/mixed deletion confirmation, partial results/retry, completed batches, already-absent deletion, and missing permission, in light and dark themes. They check explicit callback counts and do not instantiate a repository or access real Health Connect. Their instrumentation APK compiled successfully.
- The new fixture runtime/screenshots are delegated to the main verification workflow, which owns the separately managed emulator. This reviewer did not install a debug APK on the connected physical phone.
- Native provider effects, App Link verification, final deployment identity and actual UI screenshots must be reported from their respective current runs. A JVM fake gateway does not prove platform permission or provider behavior.

No critical or high-severity source finding was identified in the current scope. All three original concrete findings were fixed; the bounded tombstone limitation remains documented.
