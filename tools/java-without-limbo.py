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


def fail(path: Path, line_number: int, message: str) -> LimboFormatError:
    return LimboFormatError(f"{path}:{line_number}: malformed REBUILD-LIMBO region: {message}")


def fail_trace(path: Path, line_number: int, message: str) -> LimboFormatError:
    return LimboFormatError(f"{path}:{line_number}: malformed REBUILD-TRACE record: {message}")


def parse_live_lines(path: Path) -> list[LiveLine]:
    try:
        source_lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    except (OSError, UnicodeError) as error:
        raise LimboFormatError(f"{path}: cannot read Java source: {error}") from error

    live: list[LiveLine] = []
    state = "outside"
    milestone = ""
    start_line = 0
    trace_key: tuple[str, str] | None = None
    trace_start_line = 0

    for line_number, line in enumerate(source_lines, start=1):
        stripped = line.rstrip("\r\n")
        trace_start = TRACE_START.fullmatch(stripped)
        trace_end = TRACE_END.fullmatch(stripped)
        trace_line = TRACE_LINE.fullmatch(stripped)

        if trace_key is not None:
            if trace_start is not None or trace_line is not None:
                raise fail_trace(path, line_number, "nested traceability record")
            if trace_end is not None:
                ending_key = (trace_end.group("milestone"), trace_end.group("side"))
                if ending_key != trace_key:
                    raise fail_trace(
                        path,
                        line_number,
                        f"START{trace_key} at line {trace_start_line} closes with END{ending_key}",
                    )
                trace_key = None
                trace_start_line = 0
                continue
            if TRACE_PREFIX.search(line):
                raise fail_trace(path, line_number, "trace marker does not match the required line format")
            if not COMMENT_LINE.match(line) and not BLANK_LINE.fullmatch(stripped):
                raise fail_trace(path, line_number, "traceability records may contain comment lines only")
            continue

        if trace_end is not None:
            raise fail_trace(
                path,
                line_number,
                f"unmatched END({trace_end.group('milestone')},{trace_end.group('side')})",
            )
        if trace_start is not None:
            trace_key = (trace_start.group("milestone"), trace_start.group("side"))
            trace_start_line = line_number
            continue
        if trace_line is not None:
            continue
        if TRACE_PREFIX.search(line):
            raise fail_trace(path, line_number, "trace marker does not match the required line format")

        marker = MARKER.fullmatch(stripped)

        if MARKER_PREFIX.search(line) and marker is None:
            raise fail(path, line_number, "START/END marker does not match the required line format")

        if state == "outside":
            if marker is None:
                live.append(LiveLine(line_number, line))
            elif marker.group("kind") == "END":
                raise fail(path, line_number, f"unmatched END({marker.group('milestone')})")
            else:
                state = "awaiting_open"
                milestone = marker.group("milestone")
                start_line = line_number
            continue

        if state == "awaiting_open":
            if marker is not None:
                kind = marker.group("kind")
                if kind == "START":
                    raise fail(path, line_number, f"nested START({marker.group('milestone')})")
                raise fail(path, line_number, f"END({marker.group('milestone')}) appears before bare /*")
            if OPEN_DELIMITER.fullmatch(line.rstrip("\r\n")):
                state = "inside"
            elif not COMMENT_LINE.match(line):
                raise fail(path, line_number, "START must be followed by optional // notes and a bare /*")
            continue

        if state == "inside":
            if marker is not None:
                kind = marker.group("kind")
                if kind == "START":
                    raise fail(path, line_number, f"nested START({marker.group('milestone')})")
                raise fail(path, line_number, f"END({marker.group('milestone')}) appears before bare */")
            if CLOSE_DELIMITER.fullmatch(line.rstrip("\r\n")):
                state = "awaiting_end"
            continue

        if marker is None:
            raise fail(path, line_number, "bare */ must be immediately followed by its END marker")
        if marker.group("kind") == "START":
            raise fail(path, line_number, f"nested START({marker.group('milestone')})")
        if marker.group("milestone") != milestone:
            raise fail(
                path,
                line_number,
                f"START({milestone}) at line {start_line} closes with END({marker.group('milestone')})",
            )
        state = "outside"
        milestone = ""
        start_line = 0

    if state != "outside":
        expected = {
            "awaiting_open": "bare /* and matching END",
            "inside": "bare */ and matching END",
            "awaiting_end": f"END({milestone})",
        }[state]
        raise fail(
            path,
            len(source_lines) + 1,
            f"unmatched START({milestone}) at line {start_line}; expected {expected} before end of file",
        )
    if trace_key is not None:
        raise fail_trace(
            path,
            len(source_lines) + 1,
            f"unmatched START{trace_key} at line {trace_start_line}",
        )

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
