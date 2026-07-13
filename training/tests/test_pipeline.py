from __future__ import annotations

import unittest

from training.text_baseline import MockProvider, Retriever, validate_response


class PipelineTests(unittest.TestCase):
    def test_mock_provider_is_schema_valid_without_target_access(self) -> None:
        output = MockProvider().complete([{"role": "user", "content": "hello"}], "jp")
        self.assertEqual(validate_response(output), [])
        self.assertEqual(output["memory_candidates"], [])

    def test_retriever_is_deterministic(self) -> None:
        chunks = [
            {"chunk_id": "b", "text_jp": "おやすみなさい", "relationship_stage": "lover"},
            {"chunk_id": "a", "text_jp": "おかえりなさい", "relationship_stage": "sibling"},
        ]
        retriever = Retriever(chunks)
        first = [row["chunk_id"] for row in retriever.search("おかえり", "jp", "sibling", 2)]
        second = [row["chunk_id"] for row in retriever.search("おかえり", "jp", "sibling", 2)]
        self.assertEqual(first, second)

    def test_invalid_response_is_rejected(self) -> None:
        errors = validate_response({"reply": "x"})
        self.assertIn("object_keys_mismatch", errors)
        self.assertIn("expression_tag_invalid", errors)


if __name__ == "__main__":
    unittest.main()
