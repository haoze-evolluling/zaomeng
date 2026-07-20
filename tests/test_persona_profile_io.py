from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from src.modules.persona_profile_io import (
    merge_profile_item,
    parse_navigation_markdown,
    parse_persona_markdown,
    safe_int,
    split_metric_map,
    split_persona_value,
)


class PersonaProfileIoTests(unittest.TestCase):
    def test_markdown_parsers_preserve_navigation_and_repeated_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            navigation = root / "NAVIGATION.md"
            navigation.write_text(
                "## RUNTIME\n- load_order: SOUL -> VOICE\n\n## SOUL\n- file: SOUL.md\n",
                encoding="utf-8",
            )
            persona = root / "SOUL.md"
            persona.write_text("- core_traits: 冷静\n- core_traits: 克制\n", encoding="utf-8")

            descriptor = parse_navigation_markdown(navigation)
            parsed_persona = parse_persona_markdown(persona)

            self.assertEqual(descriptor["runtime"]["load_order"], "SOUL -> VOICE")
            self.assertEqual(descriptor["files"]["SOUL"]["file"], "SOUL.md")
            self.assertEqual(parsed_persona["core_traits"], "冷静；克制")

    def test_value_parsers_keep_metrics_and_invalid_integers_compatible(self):
        self.assertEqual(split_persona_value("冷静； 克制;果断"), ["冷静", "克制", "果断"])
        self.assertEqual(split_metric_map("勇气=8；立场=稳定；无效"), {"勇气": 8, "立场": "稳定"})
        self.assertEqual(safe_int("12"), 12)
        self.assertEqual(safe_int("unknown"), 0)

    def test_merge_profile_item_prefers_richer_profile_and_deduplicates_lists(self):
        existing = {
            "core_traits": ["冷静"],
            "typical_lines": ["先等等"],
            "decision_rules": ["先观察"],
            "speech_style": "简短",
            "values": {"智慧": 8},
        }
        incoming = {
            "core_traits": ["克制", "果断"],
            "typical_lines": ["先等等", "现在动手"],
            "decision_rules": ["先观察", "再行动"],
            "speech_style": "",
            "values": {},
        }

        merged = merge_profile_item(existing, incoming)

        self.assertEqual(merged["core_traits"], ["克制", "果断", "冷静"])
        self.assertEqual(merged["typical_lines"], ["先等等", "现在动手"])
        self.assertEqual(merged["decision_rules"], ["先观察", "再行动"])
        self.assertEqual(merged["speech_style"], "简短")
        self.assertEqual(merged["values"], {"智慧": 8})


if __name__ == "__main__":
    unittest.main()
