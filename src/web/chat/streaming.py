from __future__ import annotations

import json
import re
from typing import Any


_SPEAKER_KEY = re.compile(r'"speaker"\s*:\s*"')
_MESSAGE_KEY = re.compile(r'"message"\s*:\s*"')
_INNER_THOUGHT_KEY = re.compile(r'"inner_thought"\s*:\s*"')
_ESCAPES = {
    '"': '"',
    "\\": "\\",
    "/": "/",
    "b": "\b",
    "f": "\f",
    "n": "\n",
    "r": "\r",
    "t": "\t",
}


def encode_sse(event: str, payload: dict[str, Any]) -> str:
    event_name = re.sub(r"[^a-z0-9_-]", "", str(event or "message").lower()) or "message"
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event_name}\ndata: {data}\n\n"


def _decode_partial_json_string(text: str, start: int) -> tuple[str, bool, int]:
    """Decode a JSON string whose opening quote ends at ``start``.

    Unlike json.loads this also returns the already complete prefix while the model is
    still producing the value. Incomplete escape sequences are held until the next
    chunk, so the UI never sees broken ``\\u`` fragments.
    """

    chars: list[str] = []
    index = start
    while index < len(text):
        char = text[index]
        if char == '"':
            return "".join(chars), True, index + 1
        if char != "\\":
            chars.append(char)
            index += 1
            continue
        if index + 1 >= len(text):
            break
        escaped = text[index + 1]
        if escaped == "u":
            if index + 6 > len(text):
                break
            digits = text[index + 2 : index + 6]
            try:
                code_unit = int(digits, 16)
            except ValueError:
                chars.append("�")
                index += 6
                continue
            if 0xD800 <= code_unit <= 0xDBFF:
                if index + 6 >= len(text):
                    break
                if text[index + 6 : index + 8] == "\\u":
                    if index + 12 > len(text):
                        break
                    try:
                        low_surrogate = int(text[index + 8 : index + 12], 16)
                    except ValueError:
                        low_surrogate = -1
                    if 0xDC00 <= low_surrogate <= 0xDFFF:
                        code_point = 0x10000 + (
                            (code_unit - 0xD800) * 0x400
                        ) + (low_surrogate - 0xDC00)
                        chars.append(chr(code_point))
                        index += 12
                        continue
                chars.append("�")
            elif 0xDC00 <= code_unit <= 0xDFFF:
                chars.append("�")
            else:
                chars.append(chr(code_unit))
            index += 6
            continue
        chars.append(_ESCAPES.get(escaped, escaped))
        index += 2
    return "".join(chars), False, index


class DialogueJsonDeltaProjector:
    """Project streamed structured JSON into readable dialogue message deltas."""

    def __init__(self, *, chunk_size: int = 24) -> None:
        self.chunk_size = max(1, int(chunk_size or 1))
        self._raw = ""
        self._emitted_lengths: dict[tuple[int, str], int] = {}

    def reset(self) -> None:
        self._raw = ""
        self._emitted_lengths.clear()

    def feed(self, raw_delta: str) -> list[dict[str, Any]]:
        self._raw += str(raw_delta or "")
        projected = self._project_messages()
        events: list[dict[str, Any]] = []
        for index, speaker, message, inner_thought in projected:
            role = "scene" if speaker in {"旁白", "场景提示"} else "assistant"
            for field, value in (
                ("message", message),
                ("inner_thought", inner_thought),
            ):
                key = (index, field)
                emitted = self._emitted_lengths.get(key, 0)
                if len(value) <= emitted:
                    continue
                suffix = value[emitted:]
                self._emitted_lengths[key] = len(value)
                for offset in range(0, len(suffix), self.chunk_size):
                    events.append(
                        {
                            "index": index,
                            "speaker": speaker,
                            "role": role,
                            "field": field,
                            "text": suffix[offset : offset + self.chunk_size],
                        }
                    )
        return events

    def _project_messages(self) -> list[tuple[int, str, str, str]]:
        items: list[tuple[int, str, str, str]] = []
        speaker_matches = list(_SPEAKER_KEY.finditer(self._raw))
        for item_index, match in enumerate(speaker_matches):
            speaker, speaker_complete, speaker_end = _decode_partial_json_string(
                self._raw, match.end()
            )
            if not speaker_complete or not speaker.strip():
                continue
            boundary = (
                speaker_matches[item_index + 1].start()
                if item_index + 1 < len(speaker_matches)
                else len(self._raw)
            )
            message_match = _MESSAGE_KEY.search(self._raw, speaker_end, boundary)
            if message_match is None:
                continue
            message, _complete, _message_end = _decode_partial_json_string(
                self._raw, message_match.end()
            )
            if not message:
                continue
            inner_thought_match = _INNER_THOUGHT_KEY.search(
                self._raw, _message_end, boundary
            )
            inner_thought = ""
            if inner_thought_match is not None:
                inner_thought, _inner_complete, _inner_end = _decode_partial_json_string(
                    self._raw, inner_thought_match.end()
                )
            items.append((item_index, speaker.strip(), message, inner_thought))
        return items


__all__ = ["DialogueJsonDeltaProjector", "encode_sse"]
