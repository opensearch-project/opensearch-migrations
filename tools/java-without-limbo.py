#!/usr/bin/env python3
#
# SPDX-License-Identifier: Apache-2.0
#
"""Print or search Java code after omitting limbo and rebuild traceability records."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


MARKER = re.compile(
    r"^\s*// REBUILD-LIMBO-(?P<kind>START|END)\((?P<milestone>[^()\s]+)\)\s*$"
)
MARKER_PREFIX = re.compile(r"^\s*//\s*REBUILD-LIMBO-(?:START|END)\b")
OPEN_DELIMITER = re.compile(r"^\s*/\*\s*$")
CLOSE_DELIMITER = re.compile(r"^\s*\*/\s*$")
COMMENT_LINE = re.compile(r"^\s*//")
BLANK_LINE = re.compile(r"^\s*$")
TRACE_START = re.compile(
    r"^\s*// REBUILD-TRACE-START\("
    r"(?P<milestone>[^,()\s]+),(?P<side>source|target)"
    r"\):(?:\s+.*)?$"
)
TRACE_END = re.compile(
    r"^\s*// REBUILD-TRACE-END\("
    r"(?P<milestone>[^,()\s]+),(?P<side>source|target)"
    r"\)\s*$"
)
TRACE_LINE = re.compile(
    r"^\s*// REBUILD-TRACE\("
    r"(?P<milestone>[^,()\s]+),(?P<side>source|target)"
    r"\):(?:\s+.*)?$"
)
TRACE_PREFIX = re.compile(r"^\s*//\s*REBUILD-TRACE(?:-START|-END)?\b")


class LimboFormatError(ValueError):
    """A source file has malformed REBUILD-LIMBO scaffolding."""


@dataclass(frozen=True)
class LiveLine:
    number: int
    text: str


@dataclass
class TraceState:
    key: tuple[str, str] | None = None
    start_line: int = 0


@dataclass
class LimboState:
    name: str = "outside"
    milestone: str = ""
    start_line: int = 0


def fail(path: Path, line_number: int, message: str) -> LimboFormatError:
    return LimboFormatError(f"{path}:{line_number}: malformed REBUILD-LIMBO region: {message}")


def fail_trace(path: Path, line_number: int, message: str) -> LimboFormatError:
    return LimboFormatError(f"{path}:{line_number}: malformed REBUILD-TRACE record: {message}")


def consume_active_trace_line(
    path: Path,
    line_number: int,
    line: str,
    stripped: str,
    state: TraceState,
    trace_start: re.Match[str] | None,
    trace_end: re.Match[str] | None,
    trace_line: re.Match[str] | None,
) -> None:
    if trace_start is not None or trace_line is not None:
        raise fail_trace(path, line_number, "nested traceability record")
    if trace_end is not None:
        ending_key = (trace_end.group("milestone"), trace_end.group("side"))
        if ending_key != state.key:
            raise fail_trace(
                path,
                line_number,
                f"START{state.key} at line {state.start_line} closes with END{ending_key}",
            )
        state.key = None
        state.start_line = 0
        return
    if TRACE_PREFIX.search(line):
        raise fail_trace(path, line_number, "trace marker does not match the required line format")
    if not COMMENT_LINE.match(line) and not BLANK_LINE.fullmatch(stripped):
        raise fail_trace(path, line_number, "traceability records may contain comment lines only")


def consume_inactive_trace_line(
    path: Path,
    line_number: int,
    line: str,
    state: TraceState,
    trace_start: re.Match[str] | None,
    trace_end: re.Match[str] | None,
    trace_line: re.Match[str] | None,
) -> bool:
    if trace_end is not None:
        raise fail_trace(
            path,
            line_number,
            f"unmatched END({trace_end.group('milestone')},{trace_end.group('side')})",
        )
    if trace_start is not None:
        state.key = (trace_start.group("milestone"), trace_start.group("side"))
        state.start_line = line_number
        return True
    if trace_line is not None:
        return True
    if TRACE_PREFIX.search(line):
        raise fail_trace(path, line_number, "trace marker does not match the required line format")
    return False


def consume_trace_line(
    path: Path,
    line_number: int,
    line: str,
    stripped: str,
    state: TraceState,
) -> bool:
    trace_start = TRACE_START.fullmatch(stripped)
    trace_end = TRACE_END.fullmatch(stripped)
    trace_line = TRACE_LINE.fullmatch(stripped)
    if state.key is not None:
        consume_active_trace_line(
            path,
            line_number,
            line,
            stripped,
            state,
            trace_start,
            trace_end,
            trace_line,
        )
        return True
    return consume_inactive_trace_line(
        path,
        line_number,
        line,
        state,
        trace_start,
        trace_end,
        trace_line,
    )


def validated_limbo_marker(path: Path, line_number: int, line: str, stripped: str) -> re.Match[str] | None:
    marker = MARKER.fullmatch(stripped)
    if MARKER_PREFIX.search(line) and marker is None:
        raise fail(path, line_number, "START/END marker does not match the required line format")
    return marker


def consume_outside_limbo_line(
    path: Path,
    line_number: int,
    marker: re.Match[str] | None,
    state: LimboState,
) -> bool:
    if marker is None:
        return False
    if marker.group("kind") == "END":
        raise fail(path, line_number, f"unmatched END({marker.group('milestone')})")
    state.name = "awaiting_open"
    state.milestone = marker.group("milestone")
    state.start_line = line_number
    return True


def consume_awaiting_open_limbo_line(
    path: Path,
    line_number: int,
    line: str,
    marker: re.Match[str] | None,
    state: LimboState,
) -> bool:
    if marker is not None:
        if marker.group("kind") == "START":
            raise fail(path, line_number, f"nested START({marker.group('milestone')})")
        raise fail(path, line_number, f"END({marker.group('milestone')}) appears before bare /*")
    if OPEN_DELIMITER.fullmatch(line.rstrip("\r\n")):
        state.name = "inside"
    elif not COMMENT_LINE.match(line):
        raise fail(path, line_number, "START must be followed by optional // notes and a bare /*")
    return True


def consume_inside_limbo_line(
    path: Path,
    line_number: int,
    line: str,
    marker: re.Match[str] | None,
    state: LimboState,
) -> bool:
    if marker is not None:
        if marker.group("kind") == "START":
            raise fail(path, line_number, f"nested START({marker.group('milestone')})")
        raise fail(path, line_number, f"END({marker.group('milestone')}) appears before bare */")
    if CLOSE_DELIMITER.fullmatch(line.rstrip("\r\n")):
        state.name = "awaiting_end"
    return True


def consume_awaiting_end_limbo_line(
    path: Path,
    line_number: int,
    marker: re.Match[str] | None,
    state: LimboState,
) -> bool:
    if marker is None:
        raise fail(path, line_number, "bare */ must be immediately followed by its END marker")
    if marker.group("kind") == "START":
        raise fail(path, line_number, f"nested START({marker.group('milestone')})")
    if marker.group("milestone") != state.milestone:
        raise fail(
            path,
            line_number,
            f"START({state.milestone}) at line {state.start_line} "
            f"closes with END({marker.group('milestone')})",
        )
    state.name = "outside"
    state.milestone = ""
    state.start_line = 0
    return True


def consume_limbo_line(
    path: Path,
    line_number: int,
    line: str,
    marker: re.Match[str] | None,
    state: LimboState,
) -> bool:
    if state.name == "outside":
        return consume_outside_limbo_line(path, line_number, marker, state)
    if state.name == "awaiting_open":
        return consume_awaiting_open_limbo_line(path, line_number, line, marker, state)
    if state.name == "inside":
        return consume_inside_limbo_line(path, line_number, line, marker, state)
    return consume_awaiting_end_limbo_line(path, line_number, marker, state)


def validate_final_states(
    path: Path,
    source_line_count: int,
    limbo_state: LimboState,
    trace_state: TraceState,
) -> None:
    if limbo_state.name != "outside":
        expected = {
            "awaiting_open": "bare /* and matching END",
            "inside": "bare */ and matching END",
            "awaiting_end": f"END({limbo_state.milestone})",
        }[limbo_state.name]
        raise fail(
            path,
            source_line_count + 1,
            f"unmatched START({limbo_state.milestone}) at line {limbo_state.start_line}; "
            f"expected {expected} before end of file",
        )
    if trace_state.key is not None:
        raise fail_trace(
            path,
            source_line_count + 1,
            f"unmatched START{trace_state.key} at line {trace_state.start_line}",
        )


def parse_live_lines(path: Path) -> list[LiveLine]:
    try:
        source_lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    except (OSError, UnicodeError) as error:
        raise LimboFormatError(f"{path}: cannot read Java source: {error}") from error

    live: list[LiveLine] = []
    trace_state = TraceState()
    limbo_state = LimboState()
    for line_number, line in enumerate(source_lines, start=1):
        stripped = line.rstrip("\r\n")
        if consume_trace_line(path, line_number, line, stripped, trace_state):
            continue
        marker = validated_limbo_marker(path, line_number, line, stripped)
        if not consume_limbo_line(path, line_number, line, marker, limbo_state):
            live.append(LiveLine(line_number, line))

    validate_final_states(path, len(source_lines), limbo_state, trace_state)
    return live


def existing_java_files(values: Iterable[str]) -> list[Path]:
    paths = [Path(value) for value in values]
    for path in paths:
        if path.suffix != ".java":
            raise LimboFormatError(f"{path}: expected a .java source file")
        if not path.is_file():
            raise LimboFormatError(f"{path}: Java source file does not exist")
    return paths


def print_source(path: Path, lines: list[LiveLine]) -> int:
    del path
    for line in lines:
        sys.stdout.write(line.text)
    return 0


def search_source(
    paths_and_lines: list[tuple[Path, list[LiveLine]]], pattern: str, fixed_string: bool
) -> int:
    try:
        expression = re.compile(re.escape(pattern) if fixed_string else pattern)
    except re.error as error:
        raise LimboFormatError(f"invalid search pattern {pattern!r}: {error}") from error

    matched = False
    for path, lines in paths_and_lines:
        for line in lines:
            text = line.text.rstrip("\r\n")
            if expression.search(text):
                print(f"{path}:{line.number}:{text}")
                matched = True
    return 0 if matched else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Validate Java REBUILD-LIMBO and REBUILD-TRACE markers, omit both, then print or search."
        )
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    print_parser = subparsers.add_parser(
        "print", help="print one Java file without limbo or traceability records"
    )
    print_parser.add_argument("file")

    search_parser = subparsers.add_parser(
        "search", help="search executable-source lines and print path:original-line:text"
    )
    search_parser.add_argument("-F", "--fixed-string", action="store_true")
    search_parser.add_argument("pattern")
    search_parser.add_argument("files", nargs="+")
    return parser


def main() -> int:
    arguments = build_parser().parse_args()
    try:
        values = [arguments.file] if arguments.command == "print" else arguments.files
        paths = existing_java_files(values)
        paths_and_lines = [(path, parse_live_lines(path)) for path in paths]
        if arguments.command == "print":
            return print_source(*paths_and_lines[0])
        return search_source(paths_and_lines, arguments.pattern, arguments.fixed_string)
    except LimboFormatError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
