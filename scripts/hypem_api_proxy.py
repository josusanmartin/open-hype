#!/usr/bin/env python3
from __future__ import annotations

import http.client
import http.server
import re
import socketserver
import urllib.error
import urllib.parse
import urllib.request

UPSTREAM = "https://api.hypem.com"
MAX_REQUEST_BODY_BYTES = 1024 * 1024
SENSITIVE_QUERY_PARAMETERS = frozenset({"hm_token", "password", "token"})
SENSITIVE_LOG_VALUE = re.compile(
    r"(?i)(\b(?:hm_token|password|token)=)([^&\s]*)",
)
QUERY_LOG_PARAMETER = re.compile(r"([?&])([^=&\s]+)=([^&\s]*)")
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-connection",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "trailers",
    "transfer-encoding",
    "upgrade",
    "host",
}


def build_upstream_url(request_target: str) -> str:
    """Resolve an origin-form request target without allowing proxy-style hosts."""
    parsed = urllib.parse.urlsplit(request_target)
    if parsed.scheme or parsed.netloc or not parsed.path.startswith("/"):
        raise ValueError("request target must be an origin-form path")
    upstream = urllib.parse.urlsplit(UPSTREAM)
    return urllib.parse.urlunsplit(
        (upstream.scheme, upstream.netloc, parsed.path, parsed.query, ""),
    )


def redact_request_target(request_target: str) -> str:
    """Remove credentials from the request target before it reaches stdout."""
    parsed = urllib.parse.urlsplit(request_target)
    query = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    redacted_query = urllib.parse.urlencode(
        [
            (name, "[redacted]" if name.casefold() in SENSITIVE_QUERY_PARAMETERS else value)
            for name, value in query
        ],
        doseq=True,
    )
    return urllib.parse.urlunsplit(("", "", parsed.path, redacted_query, ""))


def redact_log_message(message: str) -> str:
    """Redact credentials from every server diagnostic, including parse errors."""
    def redact_encoded_parameter(match: re.Match[str]) -> str:
        prefix, encoded_name, value = match.groups()
        decoded_name = urllib.parse.unquote_plus(encoded_name).casefold()
        if decoded_name in SENSITIVE_QUERY_PARAMETERS:
            value = "[redacted]"
        return f"{prefix}{encoded_name}={value}"

    query_redacted = QUERY_LOG_PARAMETER.sub(redact_encoded_parameter, message)
    return SENSITIVE_LOG_VALUE.sub(r"\1[redacted]", query_redacted)


def blocked_hop_by_hop_headers(headers: http.client.HTTPMessage) -> set[str]:
    """Include extension headers nominated by each Connection header."""
    connection_tokens = {
        token.strip().casefold()
        for value in headers.get_all("Connection", [])
        for token in value.split(",")
        if token.strip()
    }
    return HOP_BY_HOP | connection_tokens


class ProxyHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        self._forward()

    def do_POST(self) -> None:
        self._forward()

    def do_PUT(self) -> None:
        self._forward()

    def do_PATCH(self) -> None:
        self._forward()

    def do_DELETE(self) -> None:
        self._forward()

    def _forward(self) -> None:
        try:
            target = build_upstream_url(self.path)
        except ValueError as error:
            self.send_error(http.HTTPStatus.BAD_REQUEST, str(error))
            return

        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            self.send_error(http.HTTPStatus.BAD_REQUEST, "invalid Content-Length")
            return
        if length < 0:
            self.send_error(http.HTTPStatus.BAD_REQUEST, "invalid Content-Length")
            return
        if length > MAX_REQUEST_BODY_BYTES:
            self.send_error(http.HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "request body is too large")
            return
        body = self.rfile.read(length) if length else None

        request = urllib.request.Request(target, data=body, method=self.command)
        blocked_request_headers = blocked_hop_by_hop_headers(self.headers) | {"content-length"}
        for name, value in self.headers.items():
            if name.casefold() not in blocked_request_headers:
                request.add_header(name, value)
        request.add_header("Host", "api.hypem.com")

        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = response.read()
                self.send_response(response.status)
                blocked_response_headers = blocked_hop_by_hop_headers(response.headers) | {"content-length"}
                for name, value in response.headers.items():
                    if name.casefold() not in blocked_response_headers:
                        self.send_header(name, value)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        except urllib.error.HTTPError as error:
            payload = error.read()
            self.send_response(error.code)
            blocked_response_headers = blocked_hop_by_hop_headers(error.headers) | {"content-length"}
            for name, value in error.headers.items():
                if name.casefold() not in blocked_response_headers:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (urllib.error.URLError, TimeoutError, OSError):
            self.send_error(http.HTTPStatus.BAD_GATEWAY, "upstream request failed")

    def log_request(self, code: int | str = "-", size: int | str = "-") -> None:
        # BaseHTTPRequestHandler's implementation formats self.requestline,
        # which contains the original unredacted query string.
        print(f"{self.command} {redact_request_target(self.path)} - {code} {size}")

    def log_message(self, fmt: str, *args: object) -> None:
        # parse_request() can include a malformed raw target in diagnostics
        # before log_request() has a chance to sanitize it.
        print(redact_log_message(fmt % args))


def main() -> None:
    with socketserver.ThreadingTCPServer(("127.0.0.1", 8787), ProxyHandler) as server:
        server.daemon_threads = True
        print("Hype API proxy listening on http://127.0.0.1:8787 (AAOS emulator reaches it via 10.0.2.2)")
        server.serve_forever()


if __name__ == "__main__":
    main()
