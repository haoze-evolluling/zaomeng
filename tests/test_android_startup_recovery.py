from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from android.app.src.main.python.zaomeng_android.recovery import (
    INTERRUPTED_MESSAGE,
    recover_interrupted_runs,
)


class AndroidStartupRecoveryTests(unittest.TestCase):
    def test_running_manifest_is_marked_interrupted_and_stopped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest_path = root / "runs" / "run-1" / "run_manifest.json"
            manifest_path.parent.mkdir(parents=True)
            manifest_path.write_text(
                json.dumps(
                    {
                        "run_id": "run-1",
                        "status": "running",
                        "success": False,
                        "locked_characters": ["甲", "乙"],
                        "progress": {
                            "stage": "distilling",
                            "message": "处理中",
                            "current_character": "乙",
                            "completed_count": 1,
                            "total_characters": 2,
                        },
                        "timing": {"started_at": "2026-07-27T10:00:00+00:00"},
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            recovered = recover_interrupted_runs(
                root,
                utc_now=lambda: "2026-07-27T10:05:00+00:00",
            )
            payload = json.loads(manifest_path.read_text(encoding="utf-8"))

            self.assertEqual(recovered, ["run-1"])
            self.assertEqual(payload["status"], "stopped")
            self.assertFalse(payload["success"])
            self.assertEqual(payload["progress"]["stage"], "interrupted")
            self.assertEqual(payload["progress"]["message"], INTERRUPTED_MESSAGE)
            self.assertEqual(payload["progress"]["current_character"], "乙")
            self.assertEqual(payload["summary"]["status_text"], "stopped")
            self.assertEqual(payload["timing"]["stopped_at"], "2026-07-27T10:05:00+00:00")
            self.assertEqual(payload["control"]["interruption_reason"], "android_process_ended")
            self.assertEqual(payload["events"][-1]["stage"], "interrupted")

    def test_terminal_manifests_are_unchanged_and_recovery_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ready_path = root / "runs" / "ready-run" / "run_manifest.json"
            ready_path.parent.mkdir(parents=True)
            ready_payload = {"run_id": "ready-run", "status": "ready", "success": True}
            ready_path.write_text(json.dumps(ready_payload), encoding="utf-8")

            first = recover_interrupted_runs(root, utc_now=lambda: "2026-07-27T10:05:00+00:00")
            second = recover_interrupted_runs(root, utc_now=lambda: "2026-07-27T10:06:00+00:00")

            self.assertEqual(first, [])
            self.assertEqual(second, [])
            self.assertEqual(json.loads(ready_path.read_text(encoding="utf-8")), ready_payload)


if __name__ == "__main__":
    unittest.main()
