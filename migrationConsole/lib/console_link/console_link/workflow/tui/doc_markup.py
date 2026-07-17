import re

from rich.markup import escape


_BOLD_RE = re.compile(r"\*\*(.+?)\*\*", re.DOTALL)


def documentation_markup(text: str) -> str:
    """Escape documentation text while allowing Markdown-style bold emphasis."""
    parts: list[str] = []
    cursor = 0
    for match in _BOLD_RE.finditer(text or ""):
        parts.append(escape(text[cursor:match.start()]))
        parts.append(f"[bold]{escape(match.group(1))}[/]")
        cursor = match.end()
    parts.append(escape(text[cursor:]))
    return "".join(parts)
