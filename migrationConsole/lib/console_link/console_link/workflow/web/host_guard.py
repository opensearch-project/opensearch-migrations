"""Accept only requests addressed to this machine's loopback names.

The Web UI has no authentication of its own; it is reached through kubectl
port-forward or an SSH tunnel, and the browser addresses it as localhost. A web
page that rebinds its own DNS name to 127.0.0.1 can reach the same port with
the browser treating it as same-origin, but its requests still carry the
attacker's name in the Host header. Rejecting any other Host closes that path.
Cross-site Origin headers are rejected too, so a page that cannot read a
response still cannot trigger an action.
"""

import json
from typing import Iterable, Optional
from urllib.parse import urlsplit

from starlette.datastructures import Headers
from starlette.types import ASGIApp, Receive, Scope, Send


LOOPBACK_HOSTS = ("localhost", "127.0.0.1", "::1")
POLICY_VIOLATION_CLOSE_CODE = 1008


def authority_hostname(authority: str) -> Optional[str]:
    """Return the lower-case hostname from a Host header value, without the port."""
    if not authority:
        return None
    try:
        return urlsplit(f"//{authority}").hostname
    except ValueError:
        return None


def origin_hostname(origin: str) -> Optional[str]:
    """Return the hostname of an Origin header; opaque origins have none."""
    try:
        parsed = urlsplit(origin)
    except ValueError:
        return None
    if parsed.scheme not in ("http", "https"):
        return None
    return parsed.hostname


class LoopbackHostMiddleware:
    """Reject requests whose Host or Origin names anything but loopback."""

    def __init__(self, app: ASGIApp, allowed_hosts: Iterable[str] = LOOPBACK_HOSTS):
        self.app = app
        self.allowed_hosts = frozenset(host.lower() for host in allowed_hosts)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return
        rejection = self._rejection(Headers(scope=scope))
        if rejection is None:
            await self.app(scope, receive, send)
        elif scope["type"] == "websocket":
            await send({"type": "websocket.close", "code": POLICY_VIOLATION_CLOSE_CODE})
        else:
            await _send_forbidden(send, *rejection)

    def _rejection(self, headers: Headers) -> Optional[tuple[str, str]]:
        if authority_hostname(headers.get("host", "")) not in self.allowed_hosts:
            return (
                "untrusted_host",
                "Workflow Web only accepts requests addressed to localhost, "
                "127.0.0.1, or [::1]. Open it through kubectl port-forward "
                "or an SSH tunnel and browse to http://127.0.0.1:<port>.",
            )
        origin = headers.get("origin")
        if origin is not None and origin_hostname(origin) not in self.allowed_hosts:
            return (
                "untrusted_origin",
                "Workflow Web only accepts requests from pages served on "
                "localhost, 127.0.0.1, or [::1].",
            )
        return None


async def _send_forbidden(send: Send, code: str, message: str) -> None:
    body = json.dumps({"detail": {"code": code, "message": message}}).encode()
    await send({
        "type": "http.response.start",
        "status": 403,
        "headers": [
            (b"content-type", b"application/json"),
            (b"content-length", str(len(body)).encode()),
        ],
    })
    await send({"type": "http.response.body", "body": body})
