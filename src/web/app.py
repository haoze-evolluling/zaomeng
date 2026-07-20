from __future__ import annotations

import hmac
import os
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from src.web.api import (
    ROUTERS,
)
from src.web.workflow import WebRunService
from src.web.path_safety import InvalidStorageIdentifier


def _request_auth_token(request: Request) -> str:
    authorization = str(request.headers.get("authorization", "")).strip()
    if authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return ""


def _token_matches(candidate: str, expected: str) -> bool:
    if not candidate or not expected:
        return False
    return hmac.compare_digest(candidate.encode("utf-8"), expected.encode("utf-8"))


def create_app(
    service: WebRunService | None = None,
    *,
    auth_token: str | None = None,
    allow_app_update: bool | None = None,
) -> FastAPI:
    app = FastAPI(title="zaomeng webui", version="0.1.0")
    app.state.run_service = service or WebRunService()
    configured_token = str(
        auth_token if auth_token is not None else os.getenv("ZAOMENG_WEB_AUTH_TOKEN", "")
    ).strip()
    app.state.web_auth_token = configured_token
    if allow_app_update is None:
        allow_app_update = str(os.getenv("ZAOMENG_WEB_ALLOW_APP_UPDATE", "0")).strip() == "1"
    app.state.allow_app_update = bool(allow_app_update)

    @app.middleware("http")
    async def require_web_auth(request: Request, call_next):
        if not configured_token:
            return await call_next(request)

        protected_path = request.url.path.startswith("/api/web/") or request.url.path in {
            "/docs",
            "/redoc",
            "/openapi.json",
        }
        if protected_path and request.url.path != "/api/web/health":
            if not _token_matches(_request_auth_token(request), configured_token):
                return JSONResponse(
                    status_code=401,
                    content={"detail": "Bearer authentication is required."},
                    headers={"WWW-Authenticate": "Bearer"},
                )
        return await call_next(request)

    @app.exception_handler(InvalidStorageIdentifier)
    async def invalid_storage_identifier_handler(_request: Request, exc: InvalidStorageIdentifier) -> JSONResponse:
        return JSONResponse(status_code=400, content={"detail": str(exc)})

    static_dir = Path(__file__).resolve().parent / "static"
    app.mount("/web", StaticFiles(directory=static_dir, html=True), name="web")

    @app.get("/")
    def root() -> FileResponse:
        return FileResponse(static_dir / "index.html")

    for router in ROUTERS:
        app.include_router(router)

    return app
