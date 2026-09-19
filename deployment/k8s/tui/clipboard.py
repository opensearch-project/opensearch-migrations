"""System clipboard helper.

Textual's App.copy_to_clipboard() writes an OSC 52 terminal escape sequence — its own
docstring says plainly "this does not work on macOS Terminal", and it only works elsewhere on
terminals that both support OSC 52 and have clipboard access enabled (iTerm2 needs it turned
on explicitly; tmux/screen need passthrough configured). For a TUI that's normally run
locally against a kubeconfig, shelling out to the OS's own clipboard tool is far more
reliable than hoping the terminal cooperates — that's tried first, with OSC 52 as the
fallback for anyone running this over SSH where a local tool isn't available.
"""
import platform
import shutil
import subprocess
from typing import List, Optional


def _clipboard_command() -> Optional[List[str]]:
    system = platform.system()
    if system == "Darwin":
        return ["pbcopy"]
    if system == "Linux":
        for cmd in (["wl-copy"], ["xclip", "-selection", "clipboard"], ["xsel", "--clipboard", "--input"]):
            if shutil.which(cmd[0]):
                return cmd
        return None
    if system == "Windows":
        return ["clip"]
    return None


def copy_via_system_tool(text: str) -> bool:
    """Try the OS's own clipboard tool. Returns True on success, False if none was available
    or it failed — callers should fall back to OSC 52 (or report failure) in that case."""
    cmd = _clipboard_command()
    if cmd is None or not shutil.which(cmd[0]):
        return False
    try:
        subprocess.run(cmd, input=text.encode("utf-8"), check=True, timeout=5)
        return True
    except Exception:
        return False
