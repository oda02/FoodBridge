# Independent Android review

Current design reviewed against official Android documentation and the actual stable `androidx.health.connect:connect-client:1.1.0` sources. This document covers the unified `items` request, application 1.2.0 / versionCode 3, minimum Android 14. It is a source/API review, not evidence of a completed release build, deployment or physical-device test.

## API compatibility

`NutritionRecord` supports the selected energy/nutrient units, `dietaryFiber`, explicit offsets and manual-entry metadata. `clientRecordId = foodbridge:SHA256(id)` remains stable; `clientRecordVersion` receives the requested revision. A higher-version insert updates a matching record, equal/lower versions are ignored, and an absent record is inserted. [NutritionRecord](https://developer.android.com/reference/androidx/health/connect/client/records/NutritionRecord), [versioned upsert](https://developer.android.com/health-and-fitness/health-connect/write-data).

Own-record reads do not require adding `READ_NUTRITION`: the native Android 14+ `HealthConnectManager.readRecords` permits an app with write permission to read its own inserted records. This corrects an earlier assumption that a write-only permission set necessarily precludes provider read-back. The production lookup requests one stable client ID and verifies the returned client ID and owner package; it returns only record UUID, revision and name for preview. Other apps' nutrition is not requested. [Permission behavior](https://developer.android.com/reference/android/health/connect/HealthConnectManager).

The exact native API is `ReadRecordsRequestUsingIds.Builder(NativeNutrition::class.java).addClientRecordId(clientId).build()`, followed by `manager.readRecords(request, executor, OutcomeReceiver<ReadRecordsResponse<NativeNutrition>, HealthConnectException>)`. A successful empty response means absent. Errors and ambiguous/mismatched responses must not be treated as absence. Jetpack 1.1.0's `readRecord` uses UUID (`addId`) and has no equivalent direct client-ID lookup; native API 34 is appropriate for the chosen minimum SDK. [Request builder](https://developer.android.com/reference/android/health/connect/ReadRecordsRequestUsingIds.Builder), [response](https://developer.android.com/reference/android/health/connect/ReadRecordsResponse), [stable SDK sources](https://dl.google.com/dl/android/maven2/androidx/health/connect/connect-client/1.1.0/connect-client-1.1.0-sources.jar).

For deletion the stable signature is `deleteRecords(recordType, recordIdsList, clientRecordIdsList)`. Production first resolves its own client ID to an owned UUID, then deletes that exact UUID using `recordIdsList = listOf(recordId)` and an empty client-ID list. This avoids broad time-range deletion. The SDK documents that absent/repeated IDs may fail, so an arbitrary exception is never sufficient proof of success. [Delete data](https://developer.android.com/health-and-fitness/health-connect/delete-data).

## Operation semantics checked in source

- The only root shape is `{ "items": [ ... ] }`, with 1–20 operations and unique explicit IDs. `upsert` contains the complete desired record. `delete` accepts only `op`, `id`, `rev`; delete revision is 2–1,000,000. Exact integer spelling is checked before numeric conversion.
- The process-wide coordinator holds its mutex across provider lookup, mutation and local history update. Both operations compare against the maximum of current provider revision and local revision. Equal/stale inputs cache the known maximum and return DUPLICATE without mutation.
- Upsert creates absent records even when the incoming revision is above 1, and distinguishes ADDED versus UPDATED using the successful lookup result.
- Delete requires a confirmation flag inside the coordinator. An absent target saves its revision locally and returns ALREADY_ABSENT. After a delete exception, only a successful second lookup proving absence allows completion; a failed lookup or still-present record remains an error.
- Local history retains the maximum revision after deletion. This tombstone blocks older upserts/deletes while retained; a higher upsert can deliberately restore the record.
- Mixed requests are a sequence of separate operations. Repository execution records each outcome and continues after ordinary per-item failures, while cancellation ends the request. There is no cross-operation atomicity or rollback.

## Important limits and lifecycle requirement

Health Connect delete does not accept a revision precondition. The app's lookup/check/delete sequence therefore is not a provider-level compare-and-swap. The shared mutex protects cooperating operations inside this process, not external system actions.

**Mutation cancellation must not release the mutex before an already-issued remote mutation completes.** A cancelled coroutine can otherwise release the lock while a provider delete is still pending, allowing a newer upsert to race with that old delete. Keep a started mutation plus local history advancement in a narrowly scoped non-cancellable section under the lock, or use an application-owned operation lifetime. Resolved in the final source: each coordinator operation executes withContext(NonCancellable) while holding the process-wide mutex, including provider completion and history advancement.

The 1000-ID history is bounded. Existing provider records recover their revision after local history loss. Deleted records cannot recover a lost tombstone; after eviction, clearing data or reinstalling, an old upsert can recreate an absent record. This is an explicit retention limitation. Generators should retain the largest revision used, including deletion revisions.

The native lookup transports a full own record from Health Connect even though the app only keeps identity/version/name. Privacy text should accurately describe these own-record checks rather than claim that the app never reads Health Connect. No additional read permission is needed.

## Required release verification

Validate single and mixed requests, malformed/duplicate keys, repeated IDs and the 20-item limit; full replacement with omitted optional nutrients; revisions 1→2→3; provider-newer-than-local data; stale deletion after cache loss; absent deletion; delete success followed by local-history failure and retry; delete exception followed by a failed lookup; restoration after tombstone; partial request failure and retry; and cancellation while a delete is in flight.

On Android 16 verify that the app has only WRITE_NUTRITION yet its targeted own-record lookup succeeds, deletion preview shows the correct own record, deletion always requires a tap, an old delete cannot remove a newer revision, and a mixed request shows accurate per-item outcomes. Check the resulting record count and values in Health Connect. JVM fakes do not establish those platform behaviors.

For App Links use the exact signed release APK and its certificate in `/.well-known/assetlinks.json`. Verify the HTTPS file without redirects, trigger `adb shell pm verify-app-links --re-verify ru.kukakur.foodbridge`, and check that `pm get-app-links` reports `food.kukakur.ru: verified`. Test links without forcing a package/component. [Official verification](https://developer.android.com/training/app-links/verify-applinks).

The original source findings concerning `dietaryFiber`, Flow.first invocation, root-path routing, Health Connect settings, future-time save gating and resetting Settings on new intents were corrected before this operation expansion. They are not open API findings in this review.

## Final source and web checkpoint

The final ViewModel parses only RequestParser requests, binds consent to the current requestId, blocks automatic requests containing deletion, and prevents future-time requests before execution. It preserves per-operation completion when retrying partial results. The UI lists deletion targets and requires an explicit confirm action. Coordinator cancellation protection is present as described above.

The website now implements the same single items envelope and bounded strict parser, including exact revision lexemes, decoded-key duplication, unique operation IDs, date/offset limits, canonical Base64URL and strict UTF-8 (a BOM is not silently stripped). It renders all user-supplied text through textContent, clears previews on hash changes, and performs no API request or browser storage. Any delete displays a manual-confirmation notice; the page itself never executes operations.

Validation run: `node --test --test-reporter=dot tests/site-security.test.cjs` — 128 tests passed, comprising all 119 shared Android/web link vectors plus nine web behavior/security checks. `node --check` passed for both browser scripts. These checks exercise parser and rendering logic with a small DOM test double; they are not a visual browser test or proof of real Health Connect behavior.
