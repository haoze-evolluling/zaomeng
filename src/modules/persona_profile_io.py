from __future__ import annotations

import re
from pathlib import Path
from typing import Any


def parse_navigation_markdown(path: Path) -> dict[str, Any]:
    parsed: dict[str, Any] = {"runtime": {}, "files": {}}
    current_section = ""
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith("## "):
            current_section = line[3:].strip().upper()
            if current_section and current_section != "RUNTIME":
                parsed["files"].setdefault(current_section, {})
            continue
        if not line.startswith("- ") or ":" not in line:
            continue
        key, value = line[2:].split(":", 1)
        key = key.strip()
        value = value.strip()
        if not value:
            continue
        if current_section == "RUNTIME":
            parsed["runtime"][key] = value
        elif current_section:
            parsed["files"].setdefault(current_section, {})[key] = value
    return parsed


def parse_persona_markdown(path: Path) -> dict[str, Any]:
    parsed: dict[str, Any] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line.startswith("- ") or ":" not in line:
            continue
        key, value = line[2:].split(":", 1)
        key = key.strip()
        value = value.strip()
        if not value:
            continue
        if key in parsed and parsed[key]:
            parsed[key] = f"{parsed[key]}；{value}"
        else:
            parsed[key] = value
    return parsed


def split_persona_value(value: str) -> list[str]:
    return [item.strip() for item in re.split(r"[；;]\s*", value) if item.strip()]


def split_metric_map(value: str) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for item in re.split(r"[；;]\s*", str(value or "").strip()):
        if not item or "=" not in item:
            continue
        key, raw = item.split("=", 1)
        key = key.strip()
        raw = raw.strip()
        if not key:
            continue
        result[key] = int(raw) if re.fullmatch(r"-?\d+", raw) else raw
    return result


def safe_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def merge_profile_item(existing: dict[str, Any] | None, incoming: dict[str, Any]) -> dict[str, Any]:
    if not existing:
        return incoming
    current_score = len(existing.get("typical_lines", [])) + len(existing.get("core_traits", []))
    incoming_score = len(incoming.get("typical_lines", [])) + len(incoming.get("core_traits", []))
    if incoming_score > current_score:
        merged = incoming.copy()
        fallback = existing
    else:
        merged = existing.copy()
        fallback = incoming

    for key in ("core_traits", "typical_lines", "decision_rules"):
        merged_values = list(merged.get(key, []))
        seen = set(merged_values)
        for item in fallback.get(key, []):
            if item not in seen:
                merged_values.append(item)
                seen.add(item)
        merged[key] = merged_values

    if not merged.get("speech_style") and fallback.get("speech_style"):
        merged["speech_style"] = fallback["speech_style"]
    if not merged.get("values") and fallback.get("values"):
        merged["values"] = fallback["values"]
    return merged


__all__ = [
    "merge_profile_item",
    "parse_navigation_markdown",
    "parse_persona_markdown",
    "safe_int",
    "split_metric_map",
    "split_persona_value",
]
