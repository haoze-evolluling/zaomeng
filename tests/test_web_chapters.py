from __future__ import annotations

import base64
import tempfile
import unittest
from unittest.mock import Mock

from src.web.workflow import WebRunService


class ChapterServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.service = WebRunService(self.tmp.name)
        self.run = self.service.create_run(
            novel_name="chapter-test.txt",
            novel_content_base64=base64.b64encode("第一章。".encode("utf-8")).decode("ascii"),
            characters=["小甲"],
            defer_run=True,
        )

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_saves_and_renders_chapter_manuscript(self) -> None:
        chapter = self.service.save_chapter(
            self.run["run_id"],
            title="第一章 雨夜",
            goal="让两人重逢",
            participants=["小甲", "小乙"],
            content="雨落在檐下。",
        )

        self.assertEqual(chapter["order"], 1)
        self.assertEqual(self.service.list_chapters(self.run["run_id"])[0]["title"], "第一章 雨夜")
        manuscript = self.service.render_chapter_manuscript(self.run["run_id"])
        self.assertIn("# chapter-test", manuscript)
        self.assertIn("## 第一章 雨夜", manuscript)
        self.assertIn("雨落在檐下。", manuscript)

    def test_archives_dialogue_transcript_as_chapter(self) -> None:
        self.service.dialogue.get_session = Mock(
            return_value={
                "session_id": "session-1",
                "title": "雨夜相逢",
                "participants": ["小甲", "小乙"],
                "transcript": [
                    {"speaker": "小甲", "message": "你来了。", "role": "user", "turn_id": "turn-1"},
                    {"speaker": "小乙", "message": "我一直在等。", "role": "character", "turn_id": "turn-1"},
                    {"speaker": "小甲", "message": "这一路辛苦了。", "role": "user", "turn_id": "turn-2"},
                    {"speaker": "小乙", "message": "不辛苦，能见到你就好。", "role": "character", "turn_id": "turn-2"},
                    {"speaker": "小甲", "message": "那我们进去说。", "role": "user", "turn_id": "turn-3"},
                    {"speaker": "小乙", "message": "好，屋里坐。", "role": "character", "turn_id": "turn-3"},
                    {"speaker": "小甲", "message": "你最近还好吗。", "role": "user", "turn_id": "turn-4"},
                    {"speaker": "小乙", "message": "挺好的，就是总想起从前。", "role": "character", "turn_id": "turn-4"},
                    {"speaker": "小甲", "message": "我也常常想起。", "role": "user", "turn_id": "turn-5"},
                    {"speaker": "小乙", "message": "那就别想太多了。", "role": "character", "turn_id": "turn-5"},
                    {"speaker": "小甲", "message": "天色不早了。", "role": "user", "turn_id": "turn-6"},
                    {"speaker": "小乙", "message": "我送送你。", "role": "character", "turn_id": "turn-6"},
                ],
            }
        )

        chapter = self.service.archive_dialogue_session_as_chapter(
            self.run["run_id"], session_id="session-1"
        )

        self.assertEqual(chapter["source_session_id"], "session-1")
        self.assertIn("小甲：你来了。", chapter["content"])
        self.assertIn("小乙：我一直在等。", chapter["content"])

    def test_archive_rejects_short_dialogue_for_novel_conversion(self) -> None:
        self.service.dialogue.get_session = Mock(
            return_value={
                "session_id": "session-short",
                "participants": ["小甲", "小乙"],
                "transcript": [
                    {"speaker": "小甲", "message": "你来了。", "role": "user", "turn_id": "turn-1"},
                    {"speaker": "小乙", "message": "我一直在等。", "role": "character", "turn_id": "turn-1"},
                ],
            }
        )

        with self.assertRaisesRegex(ValueError, "至少需要"):
            self.service.archive_dialogue_session_as_chapter(
                self.run["run_id"], session_id="session-short"
            )

    def test_continue_chapter_uses_draft_as_local_session_context(self) -> None:
        chapter = self.service.save_chapter(
            self.run["run_id"],
            title="第一章 雨夜",
            goal="让两人重逢",
            participants=["小甲"],
            content="雨落在檐下。",
        )
        self.service.create_dialogue_session = Mock(return_value={"session_id": "session-2"})

        session = self.service.continue_chapter_writing(
            self.run["run_id"], chapter["chapter_id"]
        )

        self.assertEqual(session["session_id"], "session-2")
        kwargs = self.service.create_dialogue_session.call_args.kwargs
        self.assertEqual(kwargs["participants"], ["小甲"])
        self.assertIn("雨落在檐下。", kwargs["scene_profile"]["opening_situation"])
        self.assertEqual(
            self.service.list_chapters(self.run["run_id"])[0]["last_session_id"],
            "session-2",
        )

    def test_syncs_only_new_session_entries_back_to_chapter(self) -> None:
        chapter = self.service.save_chapter(
            self.run["run_id"], title="第一章", content="原有草稿", participants=["小甲"]
        )
        book = self.service._chapter_book(self.run["run_id"])
        book["chapters"][0]["last_session_id"] = "session-3"
        self.service._write_json(self.service._chapter_book_path(self.run["run_id"]), book)
        self.service.dialogue.get_session = Mock(
            return_value={
                "transcript": [
                    {"speaker": "小甲", "message": "新的台词。"},
                    {"speaker": "小乙", "message": "新的回应。"},
                ]
            }
        )

        synced = self.service.sync_latest_session_to_chapter(self.run["run_id"], chapter["chapter_id"])
        repeated = self.service.sync_latest_session_to_chapter(self.run["run_id"], chapter["chapter_id"])

        self.assertIn("原有草稿", synced["content"])
        self.assertIn("小甲：新的台词。", synced["content"])
        self.assertEqual(synced["content"], repeated["content"])

    def test_reorders_chapters_for_manuscript_export(self) -> None:
        first = self.service.save_chapter(self.run["run_id"], title="第一章")
        second = self.service.save_chapter(self.run["run_id"], title="第二章")

        ordered = self.service.reorder_chapter(
            self.run["run_id"], second["chapter_id"], target_order=1
        )

        self.assertEqual([item["title"] for item in ordered], ["第二章", "第一章"])
        self.assertEqual(ordered[1]["chapter_id"], first["chapter_id"])

    def test_searches_local_chapters_and_sessions(self) -> None:
        self.service.save_chapter(self.run["run_id"], title="雨夜", content="小甲在桥边等候。")
        self.service.dialogue.list_sessions = Mock(return_value=[{"session_id": "session-4", "last_entry_preview": "会话末句"}])
        self.service.dialogue.get_session = Mock(return_value={"transcript": [{"speaker": "小乙", "message": "桥边的风很冷。"}]})

        results = self.service.search_run_content(self.run["run_id"], query="桥边")

        self.assertEqual([item["kind"] for item in results], ["chapter", "session"])
        self.assertIn("桥边", results[0]["preview"])


    def test_searches_persona_preview(self) -> None:
        manifest = self.service._require_manifest(self.run["run_id"])
        manifest.setdefault("artifact_index", {})["characters"] = [
            {"name": "Moss", "preview": {"core_identity": "Bridge nightwatcher"}}
        ]
        self.service._write_json(self.service._manifest_path(self.run["run_id"]), manifest)
        self.service.dialogue.list_sessions = Mock(return_value=[])

        results = self.service.search_run_content(self.run["run_id"], query="watch")

        self.assertEqual([item["kind"] for item in results], ["persona"])
        self.assertEqual(results[0]["character"], "Moss")


if __name__ == "__main__":
    unittest.main()
