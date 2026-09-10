#!/usr/bin/env python3
"""Regenerate portable unified-envelope parser vectors and complete example links."""
import importlib.util
import json
import sys
from pathlib import Path
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("link", Path(__file__).with_name("generate-link.py"))
link = importlib.util.module_from_spec(spec)
spec.loader.exec_module(link)
BASE = {"op": "upsert", "id": "meal-1", "name": "Суп 🍲", "kcal": 125.5, "time": "2020-01-15T12:34:56+03:00"}
DELETE = {"op": "delete", "id": "meal-2", "rev": 2}
vectors = []

def envelope(*items):
    return {"items": list(items)}

def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

def add(label, request, valid):
    raw = request if isinstance(request, str) else compact(request)
    vector = {"label": label, "valid": valid, "url": link.encode_bytes(raw.encode("utf-8"))}
    if valid:
        parsed = json.loads(raw)
        vector["expectedOperations"] = [
            {"op": item["op"], "id": item["id"], "rev": item.get("rev", 1),
             **({"name": item["name"]} if item["op"] == "upsert" else {})}
            for item in parsed["items"]
        ]
    vectors.append(vector)

def add_url(label, url):
    vectors.append({"label": label, "valid": False, "url": url})

add("valid single upsert Unicode", envelope(BASE), True)
add("valid complete original example", link.EXAMPLE, True)
add("valid single delete", envelope(DELETE), True)
add("valid two deletes", envelope(DELETE, {**DELETE, "id": "meal-3", "rev": 1000000}), True)
add("valid mixed operations", envelope(BASE, DELETE, {**BASE, "id": "meal-3", "rev": 3}), True)
add("valid twenty operations", envelope(*[{**DELETE, "id": f"meal-{i}"} for i in range(20)]), True)
add("valid explicit revision one", envelope({**BASE, "rev": 1}), True)
add("valid edit revision two", envelope({**BASE, "rev": 2}), True)
add("valid maximum revision", envelope({**BASE, "rev": 1000000}), True)
add("valid zero nutrients", envelope({**BASE, "kcal": 0, "p": 0, "f": 0, "c": 0, "sodiumMg": 0}), True)
add("valid maximum nutrients", envelope({**BASE, "kcal": 20000, "p": 5000, "sodiumMg": 100000}), True)
add("valid fractional negative offset", envelope({**BASE, "time": "2020-01-15T12:34:56.123456789-04:30"}), True)
add("valid explicit UTC", envelope({**BASE, "time": "2020-01-15T12:34:56Z"}), True)
add("valid escaped Unicode", json.dumps(envelope(BASE), ensure_ascii=True), True)
add("valid escaped quotes and braces", envelope({**BASE, "name": 'quoted "name" and {} []'}), True)
for field in ["op", "id", "name", "kcal", "time"]:
    add(f"missing required upsert {field}", envelope({k: v for k, v in BASE.items() if k != field}), False)
for field, value in [("kcal", -0.1), ("kcal", 20000.1), ("p", 5001), ("sodiumMg", 100001),
                     ("p", None), ("p", "12"), ("meal", "brunch"), ("name", ""), ("id", ""),
                     ("name", "\u202eevil"), ("name", "bad\nname"), ("unknown", 1), ("v", 1),
                     ("kcal", {}), ("p", []), ("time", "2020-01-15T12:34:56"),
                     ("time", "2021-02-29T00:00:00Z"), ("time", "2020-01-15T12:34:56+19:00"),
                     ("time", "1969-12-31T23:59:59Z"), ("op", "add"), ("op", None)]:
    add(f"invalid upsert {field} {value!r}", envelope({**BASE, field: value}), False)
for revision in [0, -1, 1000001, 1.0, "2", None, True, [], {}, 9223372036854775808]:
    add(f"invalid upsert revision {revision!r}", envelope({**BASE, "rev": revision}), False)
for revision in [0, 1, -1, 1000001, 2.0, "2", None, True]:
    add(f"invalid delete revision {revision!r}", envelope({**DELETE, "rev": revision}), False)
for field in ["op", "id", "rev"]:
    add(f"missing required delete {field}", envelope({k: v for k, v in DELETE.items() if k != field}), False)
for field, value in [("name", "forbidden"), ("id", ""), ("id", None), ("id", "\u202eevil"), ("id", "x" * 201), ("v", 1)]:
    add(f"invalid delete {field} {value!r}", envelope({**DELETE, field: value}), False)
add("invalid empty batch", envelope(), False)
add("invalid twenty-one operations", envelope(*[{**DELETE, "id": f"meal-{i}"} for i in range(21)]), False)
add("invalid repeated upsert id", envelope(BASE, BASE), False)
add("invalid upsert delete same id", envelope(BASE, {**DELETE, "id": BASE["id"]}), False)
add("invalid repeated delete id", envelope(DELETE, {**DELETE, "rev": 3}), False)
add("invalid bare payload", {k: v for k, v in BASE.items() if k != "op"}, False)
add("invalid versioned legacy payload", {"v": 1, **{k: v for k, v in BASE.items() if k != "op"}}, False)
add("invalid versioned envelope", {"v": 2, **envelope(BASE)}, False)
add("invalid extra top field", {"extra": True, **envelope(BASE)}, False)
for value in [None, "x", {}, 1]:
    add(f"invalid items value {value!r}", {"items": value}, False)
for item in [None, True, 1, "delete", []]:
    add(f"invalid item type {item!r}", envelope(item), False)
raw = compact(BASE)
add("duplicate literal name", '{"items":[' + raw[:-1] + ',"name":"replacement"}]}', False)
add("duplicate escaped name", '{"items":[' + raw[:-1] + ',"na\\u006de":"replacement"}]}', False)
add("duplicate operation field", '{"items":[' + raw[:-1] + ',"op":"delete"}]}', False)
add("duplicate items envelope", '{"items":[' + raw + '],"items":[]}', False)
add("duplicate escaped items envelope", '{"items":[' + raw + '],"it\\u0065ms":[]}', False)
add("duplicate escaped identity", '{"items":[' + raw + ',{"op":"delete","id":"meal-\\u0031","rev":2}]}', False)
add("invalid exponential revision", '{"items":[' + raw[:-1] + ',"rev":2e0}]}', False)
add("invalid delete exponential revision", '{"items":[{"op":"delete","id":"meal-2","rev":2e0}]}', False)
add("invalid duplicate revision", '{"items":[' + raw[:-1] + ',"rev":1,"rev":2}]}', False)
add("invalid trailing comma", '{"items":[' + raw + ',]}', False)
add("invalid trailing second object", compact(envelope(BASE)) + "{}", False)
add("invalid unpaired surrogate", compact(envelope(BASE)).replace("Суп 🍲", "\\ud800"), False)
add("invalid nonfinite exponent", compact(envelope(BASE)).replace("125.5", "1e999"), False)
add("invalid extra nesting", '{"items":[' + raw.replace("125.5", '{"nested":1}') + ']}', False)
add("invalid recursive bomb", "[" * 3000 + "0" + "]" * 3000, False)
add("invalid long token", compact(envelope(BASE)).replace("Суп 🍲", "x" * 7000), False)
add("invalid overlong decoded bytes", " " * 8193, False)
for fragment in ["A", "abc=", "ab+c", "ab/c", "Zh", "_w", "wyg", "7aCA", "%65e30", ""]:
    add_url("invalid base64url or UTF-8 " + repr(fragment), link.ORIGIN + "#" + fragment)
valid_url = link.encode(envelope(BASE))
for origin in ["http://food.kukakur.ru/", "https://food.kukakur.ru.evil.example/", "https://user@food.kukakur.ru/",
               "https://food.kukakur.ru:443/", "https://food.kukakur.ru/other", "https://food.kukakur.ru/?query=1"]:
    add_url("invalid origin or path " + origin, origin + "#" + valid_url.split("#", 1)[1])
output = ROOT / "tests" / "vectors"
output.mkdir(parents=True, exist_ok=True)
(output / "deep-links.json").write_text(json.dumps(vectors, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
edit = envelope({**link.EXAMPLE_FOOD, "rev": 2, "kcal": 370.0, "p": 14.0})
delete_example = envelope({"op": "delete", "id": link.EXAMPLE_FOOD["id"], "rev": 3})
second = {"op": "upsert", "id": "example-apple-20260911-002", "name": "Яблоко", "kcal": 80.0, "p": 0.4, "f": 0.2, "c": 21.0, "time": link.EXAMPLE_FOOD["time"], "meal": "snack"}
batch = envelope(link.EXAMPLE_FOOD, second)
mixed = envelope({**link.EXAMPLE_FOOD, "rev": 2, "kcal": 370.0}, {"op": "delete", "id": second["id"], "rev": 2})
for stem, request in [("example", link.EXAMPLE), ("example-edit", edit), ("example-delete", delete_example),
                      ("example-batch", batch), ("example-mixed", mixed)]:
    (output / f"{stem}-payload.json").write_text(json.dumps(request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / f"{stem}-url.txt").write_text(link.encode(request) + "\n", encoding="utf-8")
print(f"Wrote {len(vectors)} unified parser vectors and 5 complete example URLs to {output}")
