"""Serve the TUI as a web page instead of a terminal app, via textual-serve.

textual-serve has no CLI of its own — it's a small library that spawns the given
command as a subprocess per browser connection and streams its terminal I/O over a
websocket to an xterm.js frontend it serves. This is the local/self-hosted sibling of
textual-web (which publishes to Textualize's own hosted relay for a public URL instead).

NOTE: the TUI's 'c' (copy request) keybinding shells out to the OS clipboard tool
(pbcopy/xclip/...) on whatever machine runs THIS process — under textual-serve, that's
the server, not the browser. Fine if you're viewing it in a browser on the same machine
you're running this from; if someone else opens the URL from their own machine, 'c'
copies to your clipboard, not theirs.

Exposing this through a tunnel (ngrok, etc.)? PUBLIC_URL must be set to the tunnel's
https URL. textual-serve does NOT infer the public address from the incoming request —
the page it serves hardcodes its WebSocket/static-asset URLs from public_url (defaults
to http://{HOST}:{PORT}), so without this a remote browser loading the tunnel URL still
gets told to open a websocket to your own localhost, which resolves to THEIR machine and
silently fails.
"""
import os

from textual_serve.server import Server

NAMESPACE = os.environ.get("NAMESPACE", "ma")
HOST = os.environ.get("WEB_HOST", "localhost")
PORT = int(os.environ.get("WEB_PORT", "8321"))
PUBLIC_URL = os.environ.get("PUBLIC_URL") or None  # e.g. https://abc123.ngrok-free.app
# "or None": an unset-but-exported empty string (PUBLIC_URL="" from the wrapper script's
# default) must become None, not "" — Server's public_url default-computation only kicks
# in for None; "" is treated as a real (broken) value and produces malformed URLs.

server = Server(
    f"python3 -m tui --namespace {NAMESPACE}", host=HOST, port=PORT, public_url=PUBLIC_URL
)
server.serve()
