from __future__ import annotations

import os
import shutil
import socket
import threading
from pathlib import Path
from typing import Any

import uvicorn

from src.web.app import create_app
from src.web.workflow import WebRunService
from zaomeng_android.recovery import recover_interrupted_runs


_lock = threading.RLock()
_server: uvicorn.Server | None = None
_thread: threading.Thread | None = None
_port = 0
_error = ""


def _bundled_resource_root() -> Path:
    import src

    return Path(src.__file__).resolve().parent.parent


def _seed_missing_resources(storage_root: Path) -> None:
    bundled_root = _bundled_resource_root()
    for directory_name in ("rules", "builtin_novels", "zaomeng-skill"):
        source_root = bundled_root / directory_name
        if not source_root.is_dir():
            raise FileNotFoundError(f"Bundled resource directory is missing: {directory_name}")
        target_root = storage_root / directory_name
        for source in source_root.rglob("*"):
            if not source.is_file():
                continue
            target = target_root / source.relative_to(source_root)
            if target.exists():
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)


def _available_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def _serve(server: uvicorn.Server) -> None:
    global _error
    try:
        server.run()
    except BaseException as exc:  # surfaced to Kotlin through status()
        _error = f"{type(exc).__name__}: {exc}"


def start(storage_root: str, auth_token: str) -> int:
    """Start one process-local FastAPI server and return its loopback port."""
    global _server, _thread, _port, _error
    with _lock:
        if _thread is not None and _thread.is_alive() and _port:
            return _port

        root = Path(str(storage_root)).resolve()
        root.mkdir(parents=True, exist_ok=True)
        os.environ["ZAOMENG_RUNTIME_ROOT"] = str(root)
        _seed_missing_resources(root)
        token = str(auth_token or "").strip()
        if len(token) < 24:
            raise ValueError("The local API token must contain at least 24 characters.")

        recover_interrupted_runs(root)

        _port = _available_loopback_port()
        _error = ""
        app = create_app(
            WebRunService(root),
            auth_token=token,
            allow_app_update=False,
        )
        config = uvicorn.Config(
            app,
            host="127.0.0.1",
            port=_port,
            log_level="warning",
            access_log=False,
        )
        _server = uvicorn.Server(config)
        _thread = threading.Thread(
            target=_serve,
            args=(_server,),
            name="zaomeng-local-api",
            daemon=True,
        )
        _thread.start()
        return _port


def status() -> dict[str, Any]:
    with _lock:
        return {
            "running": bool(_thread is not None and _thread.is_alive()),
            "started": bool(_server is not None and _server.started),
            "port": _port,
            "error": _error,
        }


def stop() -> None:
    with _lock:
        if _server is not None:
            _server.should_exit = True
