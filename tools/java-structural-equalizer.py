#!/usr/bin/env python3
#
# SPDX-License-Identifier: Apache-2.0
#
"""Compare Java source after deterministic structural canonicalization."""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Iterable


TOOL_DIR = Path(__file__).resolve().parent
JAVA_SOURCE = TOOL_DIR / "JavaStructuralEqualizer.java"
DEFAULT_ROOT = "."


class EqualizerError(RuntimeError):
    """The structural comparison could not be completed."""


def run(command: list[str], cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if check and result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace").strip()
        raise EqualizerError(f"{' '.join(command)} failed ({result.returncode}): {stderr}")
    return result


def compiled_helper() -> Path:
    source = JAVA_SOURCE.read_bytes()
    digest = hashlib.sha256(source).hexdigest()[:16]
    cache = Path("/private/tmp") / f"java-structural-equalizer-{digest}"
    class_file = cache / "JavaStructuralEqualizer.class"
    if not class_file.is_file():
        cache.mkdir(parents=True, exist_ok=True)
        run(["javac", "-d", str(cache), str(JAVA_SOURCE)])
    return cache


def canonicalize_tree(root: Path) -> bytes:
    result = run(
        ["java", "-cp", str(compiled_helper()), "JavaStructuralEqualizer", "canonicalize-tree", str(root)]
    )
    return result.stdout


def safe_relative_path(value: str) -> Path:
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts:
        raise EqualizerError(f"unsafe repository path: {value}")
    return Path(*path.parts)


def java_paths_changed(repo: Path, parent: str, commit: str, root: str) -> set[str]:
    result = run(
        [
            "git",
            "diff",
            "--name-status",
            "-z",
            "--find-renames",
            "--find-copies-harder",
            parent,
            commit,
            "--",
            root,
        ],
        cwd=repo,
    )
    fields = [value.decode("utf-8") for value in result.stdout.split(b"\0") if value]
    paths: set[str] = set()
    index = 0
    while index < len(fields):
        status = fields[index]
        index += 1
        path_count = 2 if status.startswith(("R", "C")) else 1
        if index + path_count > len(fields):
            raise EqualizerError(f"malformed git name-status output after {status}")
        for value in fields[index : index + path_count]:
            if value.endswith(".java"):
                paths.add(value)
        index += path_count
    return paths


def resolve_ref(repo: Path, ref: str) -> str:
    return run(["git", "rev-parse", "--verify", f"{ref}^{{commit}}"], cwd=repo).stdout.decode().strip()


def extract_paths(repo: Path, ref: str, paths: Iterable[str], destination: Path) -> None:
    for value in sorted(paths):
        relative = safe_relative_path(value)
        exists = run(["git", "cat-file", "-e", f"{ref}:{value}"], cwd=repo, check=False)
        if exists.returncode != 0:
            continue
        contents = run(["git", "show", f"{ref}:{value}"], cwd=repo).stdout
        output = destination / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(contents)


def first_difference(left: bytes, right: bytes) -> str:
    left_lines = left.decode("utf-8", errors="replace").splitlines()
    right_lines = right.decode("utf-8", errors="replace").splitlines()
    for index, (left_line, right_line) in enumerate(zip(left_lines, right_lines), start=1):
        if left_line != right_line:
            return f"canonical line {index}: original={left_line!r}, candidate={right_line!r}"
    return f"canonical lengths differ: original={len(left_lines)} lines, candidate={len(right_lines)} lines"


def compare_bytes(left: bytes, right: bytes, report_root: Path | None) -> int:
    if left == right:
        digest = hashlib.sha256(left).hexdigest()
        print(f"PASS: structurally equivalent Java digest={digest}")
        return 0

    output = report_root or Path(tempfile.mkdtemp(prefix="java-structural-equalizer.", dir="/private/tmp"))
    output.mkdir(parents=True, exist_ok=True)
    (output / "original.canonical").write_bytes(left)
    (output / "candidate.canonical").write_bytes(right)
    print(f"FAIL: Java structures differ: {first_difference(left, right)}", file=sys.stderr)
    print(f"Canonical outputs: {output}", file=sys.stderr)
    return 1


def compare_directories(left: Path, right: Path, report_root: Path | None) -> int:
    return compare_bytes(canonicalize_tree(left), canonicalize_tree(right), report_root)


def compare_files(left: Path, right: Path, report_root: Path | None) -> int:
    with tempfile.TemporaryDirectory(prefix="java-equalizer-files.", dir="/private/tmp") as temporary:
        root = Path(temporary)
        left_root = root / "original"
        right_root = root / "candidate"
        left_root.mkdir()
        right_root.mkdir()
        shutil.copyfile(left, left_root / "Input.java")
        shutil.copyfile(right, right_root / "Input.java")
        return compare_directories(left_root, right_root, report_root)


def compare_commit_pair(arguments: argparse.Namespace) -> int:
    repo = arguments.repo.resolve()
    original_parent = resolve_ref(repo, arguments.original_parent)
    original = resolve_ref(repo, arguments.original)
    candidate_parent = resolve_ref(repo, arguments.candidate_parent)
    candidate = resolve_ref(repo, arguments.candidate)
    paths = java_paths_changed(repo, original_parent, original, arguments.root)
    paths.update(java_paths_changed(repo, candidate_parent, candidate, arguments.root))

    with tempfile.TemporaryDirectory(prefix="java-equalizer-commits.", dir="/private/tmp") as temporary:
        root = Path(temporary)
        original_root = root / "original"
        candidate_root = root / "candidate"
        original_root.mkdir()
        candidate_root.mkdir()
        extract_paths(repo, original, paths, original_root)
        extract_paths(repo, candidate, paths, candidate_root)
        result = compare_directories(original_root, candidate_root, arguments.report_dir)

    print(
        "Compared commit pair: "
        f"original={original_parent}..{original} "
        f"candidate={candidate_parent}..{candidate} "
        f"java_paths={len(paths)}"
    )
    return result


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description=(
            "Strip comments, normalize Java tokens, sort order-insensitive declarations, preserve "
            "initialization order, and compare canonical structures."
        )
    )
    subparsers = result.add_subparsers(dest="command", required=True)

    files = subparsers.add_parser("compare-files")
    files.add_argument("original", type=Path)
    files.add_argument("candidate", type=Path)
    files.add_argument("--report-dir", type=Path)

    directories = subparsers.add_parser("compare-directories")
    directories.add_argument("original", type=Path)
    directories.add_argument("candidate", type=Path)
    directories.add_argument("--report-dir", type=Path)

    commits = subparsers.add_parser("compare-commit-pair")
    commits.add_argument("--repo", type=Path, default=Path.cwd())
    commits.add_argument("--original-parent", required=True)
    commits.add_argument("--original", required=True)
    commits.add_argument("--candidate-parent", required=True)
    commits.add_argument("--candidate", required=True)
    commits.add_argument("--root", default=DEFAULT_ROOT)
    commits.add_argument("--report-dir", type=Path)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "compare-files":
            return compare_files(arguments.original, arguments.candidate, arguments.report_dir)
        if arguments.command == "compare-directories":
            return compare_directories(arguments.original, arguments.candidate, arguments.report_dir)
        return compare_commit_pair(arguments)
    except (EqualizerError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
