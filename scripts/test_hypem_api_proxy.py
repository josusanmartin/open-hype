from __future__ import annotations

import unittest
from contextlib import redirect_stdout
from email.message import Message
from io import StringIO

from hypem_api_proxy import (
    ProxyHandler,
    blocked_hop_by_hop_headers,
    build_upstream_url,
    redact_log_message,
    redact_request_target,
)


class BuildUpstreamUrlTest(unittest.TestCase):
    def test_keeps_path_and_query_on_the_fixed_upstream(self) -> None:
        self.assertEqual(
            build_upstream_url("/v2/tracks?mode=latest&page=2"),
            "https://api.hypem.com/v2/tracks?mode=latest&page=2",
        )

    def test_rejects_an_absolute_proxy_target(self) -> None:
        with self.assertRaises(ValueError):
            build_upstream_url("https://example.com/collect?hm_token=secret")

    def test_rejects_a_scheme_relative_target(self) -> None:
        with self.assertRaises(ValueError):
            build_upstream_url("//example.com/collect?hm_token=secret")


class RedactRequestTargetTest(unittest.TestCase):
    def test_redacts_tokens_without_hiding_safe_query_values(self) -> None:
        redacted = redact_request_target(
            "/v2/favorites?hm_token=secret&page=2&TOKEN=another-secret",
        )

        self.assertEqual(
            redacted,
            "/v2/favorites?hm_token=%5Bredacted%5D&page=2&TOKEN=%5Bredacted%5D",
        )
        self.assertNotIn("secret", redacted)

    def test_handler_request_log_never_reprints_the_original_request_line(self) -> None:
        handler = object.__new__(ProxyHandler)
        handler.command = "GET"
        handler.path = "/v2/favorites?hm_token=secret&page=2"
        handler.requestline = "GET /v2/favorites?hm_token=secret&page=2 HTTP/1.1"
        output = StringIO()

        with redirect_stdout(output):
            handler.log_request(200, 42)

        self.assertNotIn("secret", output.getvalue())
        self.assertIn("hm_token=%5Bredacted%5D", output.getvalue())

    def test_malformed_request_diagnostic_redacts_its_raw_target(self) -> None:
        message = "Bad request syntax ('GET /v2/favorites?hm_token=secret HTTP/1.1')"

        redacted = redact_log_message(message)

        self.assertNotIn("secret", redacted)
        self.assertIn("hm_token=[redacted]", redacted)

    def test_malformed_diagnostic_redacts_percent_encoded_sensitive_name(self) -> None:
        message = "Bad request syntax ('GET /v2/favorites?%68m_token=secret HTTP/1.1')"

        redacted = redact_log_message(message)

        self.assertNotIn("secret", redacted)
        self.assertIn("%68m_token=[redacted]", redacted)


class HopByHopHeaderTest(unittest.TestCase):
    def test_blocks_headers_named_by_connection(self) -> None:
        headers = Message()
        headers.add_header("Connection", "keep-alive, X-Internal-Hop")
        headers.add_header("Trailer", "Digest")

        blocked = blocked_hop_by_hop_headers(headers)

        self.assertIn("keep-alive", blocked)
        self.assertIn("x-internal-hop", blocked)
        self.assertIn("trailer", blocked)


if __name__ == "__main__":
    unittest.main()
