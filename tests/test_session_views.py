from __future__ import annotations

import unittest

from src.web.chat.session_views import (
    build_pending_turn_summary,
    build_session_card,
    serialize_scene_history,
    serialize_transcript,
)


class SessionViewsTests(unittest.TestCase):
    def test_transcript_assigns_roles_by_mode(self):
        session = {
            "mode": "act",
            "controlled_character": "甲",
            "history": [
                {"speaker": "甲", "message": "我来。"},
                {"speaker": "乙", "message": "等等。"},
                {"speaker": "场景提示", "message": "天色暗了。"},
            ],
        }

        transcript = serialize_transcript(session)

        self.assertEqual([item["role"] for item in transcript], ["user", "character", "scene"])

    def test_session_card_and_scene_history_keep_public_shape(self):
        session = {
            "mode": "observe",
            "participants": ["甲", "乙"],
            "scene_card_id": "scene-2",
            "scene_card": {"title": "后院"},
            "scene_history": [
                {"scene_card_id": "scene-1", "title": "前厅"},
                {"scene_card_id": "scene-2", "title": "后院"},
            ],
        }

        card = build_session_card(session, mode_display=lambda mode: f"display:{mode}")
        history = serialize_scene_history(session)

        self.assertEqual(card["mode_display"], "display:observe")
        self.assertEqual(history[0]["is_current"], "")
        self.assertEqual(history[1]["is_current"], "true")

    def test_pending_summary_normalizes_message_kind(self):
        summary = build_pending_turn_summary(
            {
                "pending_turn": {
                    "turn_id": "turn-1",
                    "speaker": "User",
                    "user_message": "继续",
                    "message_kind": "invalid",
                    "response_limit_hint": 2,
                }
            },
            normalize_message_kind=lambda kind: "dialogue" if kind == "invalid" else kind,
        )

        self.assertEqual(summary["message_kind"], "dialogue")
        self.assertEqual(summary["response_limit_hint"], 2)


if __name__ == "__main__":
    unittest.main()
