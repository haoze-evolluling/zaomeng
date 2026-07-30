from __future__ import annotations

import unittest

from src.web.chat.helpers import parse_dialogue_responses


class DialogueJsonParsingTests(unittest.TestCase):
    def test_parse_dialogue_responses_ignores_explanation_before_json(self) -> None:
        responses = parse_dialogue_responses(
            '我会按格式回答。\n[{"speaker":"甲","message":"我知道了。"}]',
            ["甲"],
        )
        self.assertEqual(responses, [{"speaker": "甲", "message": "我知道了。"}])

    def test_parse_dialogue_responses_ignores_thinking_and_code_fence(self) -> None:
        content = "<think>先判断人物关系。</think>\n```json\n[{\"speaker\":\"甲\",\"message\":\"继续。\"}]\n```"
        responses = parse_dialogue_responses(content, ["甲"])
        self.assertEqual(responses, [{"speaker": "甲", "message": "继续。"}])

    def test_parse_dialogue_responses_prefers_response_array_over_other_json(self) -> None:
        content = '{"analysis":"ignored"}\n[{"speaker":"甲","message":"括号 { 和 [ 都在台词里。"}]\n{"done":true}'
        responses = parse_dialogue_responses(content, ["甲"])
        self.assertEqual(responses[0]["message"], "括号 { 和 [ 都在台词里。")

    def test_parse_dialogue_responses_rejects_truncated_json(self) -> None:
        with self.assertRaisesRegex(ValueError, "not valid JSON"):
            parse_dialogue_responses('[{"speaker":"甲","message":"未完成"}', ["甲"])

    def test_parse_dialogue_responses_accepts_literal_newlines_in_messages(self) -> None:
        content = """[
  {
    "speaker": "祥子",
    "message": "你好
这是第二行"
  }
]"""
        responses = parse_dialogue_responses(content, ["祥子", "小福子"])
        self.assertEqual(len(responses), 1)
        self.assertEqual(responses[0]["speaker"], "祥子")
        self.assertIn("第二行", responses[0]["message"])


if __name__ == "__main__":
    unittest.main()
