from __future__ import annotations

import base64
import tempfile
import unittest

from fastapi.testclient import TestClient

from src.web.app import create_app
from src.web.workflow import WebRunService


class AndroidDialogueRecoveryTests(unittest.TestCase):
    def test_recover_dialogue_session_aborts_pending_turn(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="test-model",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            run = service.create_run(
                novel_name="demo.txt",
                novel_content_base64=base64.b64encode("甲看向乙。".encode()).decode(),
                characters=["甲", "乙"],
            )
            for character in ("甲", "乙"):
                profile = (
                    f"- name: {character}\n"
                    "- novel_id: demo\n"
                    "- core_identity: 故事人物\n"
                )
                service.ingest_character_result(
                    run["run_id"],
                    character=character,
                    content_base64=base64.b64encode(profile.encode()).decode(),
                )

            manifest = service._require_manifest(run["run_id"])
            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["甲", "乙"],
            )
            service.dialogue.prepare_turn(
                manifest,
                session_id=session["session_id"],
                message="继续。",
            )

            client = TestClient(create_app(service))
            response = client.post(
                f"/api/web/runs/{run['run_id']}/dialogue/sessions/"
                f"{session['session_id']}/recover"
            )
            recovered = response.json()
            raw = service.dialogue._read_json(
                service.dialogue._session_file(run["run_id"], session["session_id"])
            )

            self.assertEqual(response.status_code, 200)
            self.assertEqual(recovered["status"], "ready")
            self.assertEqual(raw["pending_turn"], {})
            self.assertEqual(raw["aborted_turns"][-1]["reason"], "client_recovery")


if __name__ == "__main__":
    unittest.main()
