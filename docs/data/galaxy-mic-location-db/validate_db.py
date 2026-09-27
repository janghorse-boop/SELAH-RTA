"""Validate the documentary DB and perform conservative exact-code lookup.
Python 3.10+. No third-party package is required.
Usage:
  python validate_db.py ToneVista_Galaxy_Microphone_DB_v0_1.json
  python validate_db.py ToneVista_Galaxy_Microphone_DB_v0_1.json --model SM-X710
"""
from __future__ import annotations
import argparse
import json
import sys
from pathlib import Path
from typing import Any

def normalize_model_code(value: str) -> str:
    # Never drop regional, carrier, DS or UD suffixes.
    return value.strip().upper()

def validate(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    models = data.get("models", [])
    pending = data.get("pending_models", [])
    sources = data.get("sources", [])
    source_ids = {s["source_id"] for s in sources}
    model_ids: set[str] = set()
    aliases: dict[str, str] = {}
    location_ids: set[str] = set()
    if len(source_ids) != len(sources):
        errors.append("Duplicate source_id")
    for model in models:
        mid = model["model_id"]
        if mid in model_ids:
            errors.append(f"Duplicate model_id: {mid}")
        model_ids.add(mid)
        locations = model.get("locations", [])
        if model.get("documented_mic_location_count") != len(locations):
            errors.append(f"{mid}: location count mismatch")
        if model.get("orientation_id") not in data.get("orientation_conventions", {}):
            errors.append(f"{mid}: undefined orientation")
        for field in ("physical_microphone_capsule_count", "active_android_microphone_ids",
                      "physical_to_android_mapping", "independent_microphone_selection_supported",
                      "default_spl_offset_db"):
            if model.get(field) is not None:
                errors.append(f"{mid}: unverified field must be null: {field}")
        for source_id in model.get("source_ids", []):
            if source_id not in source_ids:
                errors.append(f"{mid}: unknown source {source_id}")
        codes = model.get("documented_model_codes", [])
        if not codes and model.get("automatic_lookup_policy") != "disabled_manual_reference_only":
            errors.append(f"{mid}: no verified alias but automatic lookup is enabled")
        for code in codes:
            normalized = normalize_model_code(code)
            if normalized in aliases:
                errors.append(f"Duplicate alias: {normalized}")
            aliases[normalized] = mid
        for location in locations:
            lid = location["location_key"]
            if lid in location_ids:
                errors.append(f"Duplicate location_key: {lid}")
            location_ids.add(lid)
            evidence = location["evidence"]
            if evidence["source_id"] not in source_ids:
                errors.append(f"{lid}: undefined source")
            page, index = evidence.get("printed_page"), evidence.get("pdf_page_index")
            if (page is None) != (index is None):
                errors.append(f"{lid}: incomplete page evidence")
            if page is not None and index != page - 1:
                errors.append(f"{lid}: page-index mismatch for this document set")
            if location.get("android_microphone_id") is not None:
                errors.append(f"{lid}: static Android ID mapping is prohibited in this release")
    for model in pending:
        if model["model_id"] in model_ids:
            errors.append(f"Completed/pending overlap: {model['model_id']}")
        model_ids.add(model["model_id"])
        if model.get("automatic_lookup_enabled") is not False:
            errors.append(f"Pending record enabled: {model['model_id']}")
        if model.get("documented_mic_location_count") is not None or model.get("locations") is not None:
            errors.append(f"Pending record has guessed values: {model['model_id']}")
    expected = {
        "verified_commercial_models":len(models),
        "phones":sum(m["category"] == "phone" for m in models),
        "tablets":sum(m["category"] == "tablet" for m in models),
        "documented_location_rows":sum(len(m["locations"]) for m in models),
        "pending_commercial_models":len(pending),
        "source_documents":len(sources),
    }
    for key, value in expected.items():
        if data.get("counts", {}).get(key) != value:
            errors.append(f"Summary mismatch: {key}")
    return errors

def exact_lookup(data: dict[str, Any], code: str) -> dict[str, Any] | None:
    normalized = normalize_model_code(code)
    for model in data["models"]:
        if model["automatic_lookup_policy"] != "exact_documented_code_only":
            continue
        if normalized in {normalize_model_code(x) for x in model["documented_model_codes"]}:
            return model
    return None

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("--model", help="Exact documented model code; suffixes are preserved.")
    args = parser.parse_args()
    try:
        data = json.loads(args.database.read_text(encoding="utf-8"))
        errors = validate(data)
        if errors:
            for error in errors:
                print(f"ERROR: {error}", file=sys.stderr)
            return 1
        print(json.dumps({"validation":"PASS", **data["counts"]}, ensure_ascii=False))
        if args.model:
            found = exact_lookup(data, args.model)
            print(json.dumps({
                "query":args.model,
                "status":"documentary_reference" if found else "unknown",
                "model_id":found["model_id"] if found else None,
                "documented_mic_location_count":found["documented_mic_location_count"] if found else None,
                "device_tested":False,
            }, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
