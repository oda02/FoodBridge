#!/usr/bin/env python3
"""Encode the unified FoodBridge items envelope as an unpadded UTF-8 base64url link.

Usage: python scripts/generate-link.py request.json
       python scripts/generate-link.py --example
       Get-Content request.json -Raw | python scripts/generate-link.py -
The application performs full schema validation before any operation.
"""
import argparse
import base64
import json
import sys
from pathlib import Path

ORIGIN = "https://food.kukakur.ru/"
EXAMPLE_FOOD = {"op": "upsert", "id": "example-oatmeal-20260911-001", "name": "Овсянка с ягодами 🥣", "kcal": 352.5,
                "time": "2026-09-11T09:00:00+03:00", "p": 12.4, "f": 8.1, "c": 54.0, "fiber": 7.2,
                "sugar": 9.6, "saturatedFat": 1.8, "sodiumMg": 125, "meal": "breakfast"}
EXAMPLE = {"items": [EXAMPLE_FOOD]}

def encode_bytes(data: bytes) -> str:
    return ORIGIN + "#" + base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")

def encode(request: dict) -> str:
    if set(request) != {"items"} or not isinstance(request["items"], list) or not 1 <= len(request["items"]) <= 20:
        raise ValueError("Use the unified envelope: {\"items\": [1..20 operations]}")
    return encode_bytes(json.dumps(request, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8"))

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result

def invalid_constant(value):
    raise ValueError(f"Invalid JSON number: {value}")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", nargs="?", help="JSON request path, or - for stdin")
    parser.add_argument("--example", action="store_true", help="Print the deterministic single-upsert example link")
    args = parser.parse_args()
    try:
        if args.example:
            request = EXAMPLE
        elif args.file:
            source = sys.stdin.read() if args.file == "-" else Path(args.file).read_text(encoding="utf-8-sig")
            request = json.loads(source, object_pairs_hook=unique_object, parse_constant=invalid_constant)
            if not isinstance(request, dict):
                parser.error("Request must be an object with an items array")
        else:
            parser.error("Specify a JSON file, - for stdin, or --example")
        result = encode(request)
        if len(result) > 12000 or len(base64.urlsafe_b64decode(result.split("#", 1)[1] + "===")) > 8192:
            parser.error("Payload exceeds the application's size limit")
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(result)

if __name__ == "__main__":
    main()
