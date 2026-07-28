from __future__ import annotations

from pathlib import Path
from typing import Callable
import json

from src.web.manifest import load_json_file, write_json_file
from src.web.run_ops.state import finalize_manifest_timing, project_manifest_summary
from src.web.time_utils import utc_now as _utc_now


INTERRUPTED_MESSAGE = "上次蒸馏因应用进程结束而中断，可重新蒸馏继续。"
STARTUP_REPORT_NAME = "android_startup_report.json"


def audit_runtime_storage(storage_root: str | Path) -> dict[str, object]:
    """Perform a non-destructive startup audit and persist a share-safe summary."""
    root = Path(storage_root)
    issues: list[dict[str, str]] = []
    runs_root = root / "runs"
    run_count = 0
    if runs_root.is_dir():
        for run_dir in sorted(path for path in runs_root.iterdir() if path.is_dir()):
            run_count += 1
            manifest_path = run_dir / "run_manifest.json"
            if not manifest_path.is_file():
                issues.append({"kind": "missing_manifest", "run_id": run_dir.name})
                continue
            if load_json_file(manifest_path) is None:
                issues.append({"kind": "invalid_manifest", "run_id": run_dir.name})
    settings_path = root / "model_settings.json"
    if settings_path.is_file():
        try:
            parsed = json.loads(settings_path.read_text(encoding="utf-8"))
            if not isinstance(parsed, dict):
                raise ValueError("settings root is not an object")
        except (OSError, ValueError, TypeError):
            issues.append({"kind": "invalid_model_settings", "run_id": ""})
    report: dict[str, object] = {
        "checked_at": _utc_now(),
        "run_count": run_count,
        "issues": issues,
        "recovered_run_ids": [],
    }
    write_json_file(root / STARTUP_REPORT_NAME, report)
    return report


def record_startup_recovery(storage_root: str | Path, recovered_run_ids: list[str]) -> None:
    report_path = Path(storage_root) / STARTUP_REPORT_NAME
    report = load_json_file(report_path) or {}
    report["recovered_run_ids"] = list(recovered_run_ids)
    write_json_file(report_path, report)


def recover_interrupted_runs(
    storage_root: str | Path,
    *,
    utc_now: Callable[[], str] = _utc_now,
) -> list[str]:
    """Stop Android runs whose process-local worker disappeared with the app process."""
    recovered_run_ids: list[str] = []
    runs_root = Path(storage_root) / "runs"
    if not runs_root.is_dir():
        return recovered_run_ids

    for manifest_path in sorted(runs_root.glob("*/run_manifest.json")):
        manifest = load_json_file(manifest_path)
        if not manifest or str(manifest.get("status", "")).strip() != "running":
            continue

        now_text = utc_now()
        run_id = str(manifest.get("run_id", "")).strip() or manifest_path.parent.name
        manifest["status"] = "stopped"
        manifest["success"] = False
        manifest["updated_at"] = now_text

        progress = manifest.setdefault("progress", {})
        progress["stage"] = "interrupted"
        progress["message"] = INTERRUPTED_MESSAGE

        control = manifest.setdefault("control", {})
        control["interrupted_at"] = now_text
        control["interruption_reason"] = "android_process_ended"
        if bool(control.get("stop_requested", False)):
            control["stop_acknowledged_at"] = (
                str(control.get("stop_acknowledged_at", "")).strip() or now_text
            )

        finalize_manifest_timing(manifest, outcome="stopped", now_text=now_text)
        manifest.setdefault("capabilities", {})["verify_workflow"] = {
            "status": "stopped",
            "success": False,
            "updated_at": now_text,
            "message": "automatic workflow interrupted after Android process restart",
        }
        manifest.setdefault("events", []).append(
            {
                "stage": "interrupted",
                "status": "stopped",
                "message": INTERRUPTED_MESSAGE,
                "character": str(progress.get("current_character", "")).strip(),
                "capability": "verify_workflow",
                "timestamp": now_text,
            }
        )
        project_manifest_summary(manifest)
        write_json_file(manifest_path, manifest)
        recovered_run_ids.append(run_id)

    return recovered_run_ids
