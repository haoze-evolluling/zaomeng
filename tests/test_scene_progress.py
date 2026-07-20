from __future__ import annotations

import unittest

from src.web.chat.scene_progress import derive_scene_progress_state, merge_scene_progress_state
from src.web.chat.state_utils import empty_session_state, set_session_scene_progress


class SceneProgressTests(unittest.TestCase):
    def test_merge_keeps_departed_participant_offstage(self):
        state = empty_session_state(1)
        set_session_scene_progress(
            state,
            {
                "present_participants": ["林黛玉", "贾宝玉"],
                "offstage_participants": ["薛宝钗"],
                "time_hint": "夜里",
                "location": "花厅",
            },
            updated_at="2026-07-20T00:00:00Z",
        )
        session = {
            "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
            "history": [
                {
                    "speaker": "场景提示",
                    "message": "薛宝钗先回房，只剩林黛玉和贾宝玉在花厅。",
                    "ts": "2026-07-20T00:00:00Z",
                }
            ],
            "state": state,
        }

        merged = merge_scene_progress_state(
            session,
            {
                "present_participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                "offstage_participants": [],
            },
            transcript=[],
            state_version=1,
        )

        self.assertEqual(merged["present_participants"], ["林黛玉", "贾宝玉"])
        self.assertEqual(merged["offstage_participants"], ["薛宝钗"])

    def test_departure_event_can_create_scene_shift_pressure(self):
        state = empty_session_state(1)
        set_session_scene_progress(
            state,
            {
                "present_participants": ["林黛玉", "贾宝玉"],
                "offstage_participants": ["薛宝钗"],
                "time_hint": "夜里",
                "location": "花厅",
                "atmosphere_summary": "花厅安静下来",
            },
            updated_at="2026-07-20T00:00:00Z",
        )
        state["signals"] = {
            "recent": [
                {
                    "kind": "cast_exit",
                    "actor": "薛宝钗",
                    "cue": "薛宝钗离场",
                    "ts": "2026-07-20T00:00:00Z",
                }
            ],
            "by_type": {},
            "updated_at": "2026-07-20T00:00:00Z",
        }
        history = [
            {
                "speaker": "场景提示" if index == 0 else "林黛玉",
                "message": "薛宝钗先回房。" if index == 0 else f"这一拍继续推进{index}。",
                "ts": f"2026-07-20T00:00:0{index}Z",
            }
            for index in range(4)
        ]
        session = {
            "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
            "history": history,
            "state": state,
        }
        transcript = [
            {"role": "scene", "message": item["message"]}
            for item in history
        ]

        derived = derive_scene_progress_state(
            session,
            transcript,
            state_version=1,
        )

        self.assertTrue(derived["should_offer_scene_shift"])
        self.assertIn("薛宝钗已经离场", derived["scene_shift_reason"])
        self.assertGreaterEqual(derived["beat_maturity"], 42)


if __name__ == "__main__":
    unittest.main()
