# Unified request parser vectors

All public FoodBridge links use one JSON envelope:

    {"items":[{"op":"upsert","id":"serving-id","name":"Soup","kcal":125,"time":"2020-01-15T12:00:00+03:00"}]}

There is no schema version field and no bare single-meal format. A request has
1–20 operations with unique explicit IDs. A single operation uses the same items
array as a batch. Upserts accept optional rev (default 1); deletes have exactly
op, id, and rev, with rev at least 2. All revisions use exact integer tokens
and are at most 1000000. The application's coordinator separately decides whether
an operation is eligible based on previously saved revisions and permissions.

deep-links.json contains 119 deterministic complete HTTPS links. Each entry has
a label, valid, and url. Valid entries include expectedOperations (op, id, rev,
and name for upserts). Both Android and browser validators can consume this
portable file without network access or a Health Connect provider.

From the repository root:

    python scripts/generate-test-vectors.py
    python scripts/generate-link.py --example
    python scripts/generate-link.py tests/vectors/example-batch-payload.json
    .\gradlew.bat testDebugUnitTest

The example payload and URL pairs are:

- example: one complete Unicode serving.
- example-edit: revision 2 of that serving.
- example-delete: revision 3 deletion of that serving.
- example-batch: two distinct original servings.
- example-mixed: update the first serving and delete the second, after originals.

These IDs and dates are fixed for reproducibility. Repeated imports intentionally
reuse the same IDs/revisions; choose new IDs to represent different servings.

RequestParserTest validates the actual envelope and every portable vector.
DeepLinkParserTest validates internal individual food fields, without the
envelope's op field. Internal food-field objects are not a supported link format.

Coverage includes origin/path rules, canonical base64url, malformed UTF-8,
Unicode/control/surrogate rules, duplicate decoded keys and serving IDs,
malformed JSON, bounded nesting and long tokens, required fields, exact revision
spelling, time offsets and calendar errors, nutrient ranges, absent versus zero
optional nutrients, operation combinations, deletion identity rules, 20-item
limits, and rejection of old/versioned formats. Parser tests perform no inserts,
updates, deletions, permission requests, or other device/provider interactions.
