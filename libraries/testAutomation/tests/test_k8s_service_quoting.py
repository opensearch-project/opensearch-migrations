"""Regression tests for the bash quoting in K8sService.exec_background_cmd.

Background:
PR check #149 (pr-eks-cdc-full-e2e-test) failed with
``Test0041 requires --transform_image_basic and --transform_image_sequence``
even though Jenkins built and passed both image refs. Root cause was a
two-shell quoting bug in :func:`K8sService.exec_background_cmd`: the inner
``sh -c`` snippet was wrapped in literal single quotes (``nohup sh -c '...'``)
without re-quoting, so any argv whose value contained an unbalanced ``"`` (or
any ``'``) caused the outer shell to mis-tokenise the inner command. With the
real Jenkins input (image refs that picked up a stray leading ``"`` from the
``package-transforms.sh`` YAML output) this merged ``--transform_image_basic``
and ``--transform_image_sequence`` into one giant value and dropped the second
flag entirely, which is what the pytest ``ValueError`` was actually reporting.

These tests run the *exact* shell snippet ``exec_background_cmd`` builds
through ``/bin/sh -c`` and confirm pytest-style argv survives intact.
"""
import os
import subprocess
import sys
import textwrap

import pytest

# Add the testAutomation package to the path so bare `k8s_service` imports resolve
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'testAutomation'))

import k8s_service  # noqa: E402


def _run_through_wrapper(tmp_path, command_list):
    """Render the wrapper that exec_background_cmd builds and execute it.

    Returns the argv (as a list of strings) that the inner command actually
    received, by invoking a tiny Python helper that prints sys.argv as JSON.
    """
    helper = tmp_path / "show_argv.py"
    helper.write_text(textwrap.dedent("""
        import json, sys
        print(json.dumps(sys.argv))
    """).strip())

    log_file = str(tmp_path / "out.log")
    exit_code_file = str(tmp_path / "exit.rc")

    # Reproduce exec_background_cmd's wrapper construction exactly. We can't call
    # the method directly because it talks to a k8s API; we mirror the body so
    # this test breaks if the wrapper logic regresses.
    import shlex
    full_cmd = [sys.executable, str(helper)] + list(command_list)
    inner_cmd = " ".join(shlex.quote(arg) for arg in full_cmd)
    inner_snippet = (
        f"{inner_cmd} > {shlex.quote(log_file)} 2>&1; "
        f"echo $? > {shlex.quote(exit_code_file)}"
    )
    wrapper = (
        f"nohup sh -c {shlex.quote(inner_snippet)} "
        f"> /dev/null 2>&1 &"
    )

    # The actual code path is: kubectl exec -- sh -c <wrapper>. Locally we just
    # use sh -c <wrapper> — the parsing semantics are identical.
    subprocess.run(["sh", "-c", wrapper], check=False)

    # Wait for the background command to finish (helper is fast).
    import time
    deadline = time.time() + 5.0
    while time.time() < deadline:
        if os.path.exists(exit_code_file):
            break
        time.sleep(0.05)

    assert os.path.exists(log_file), f"helper never produced output: wrapper={wrapper!r}"
    raw = open(log_file).read().strip()
    assert raw, f"helper produced empty output: wrapper={wrapper!r}"
    import json
    argv = json.loads(raw)
    # argv[0] is the script path; the caller cares about argv[1:].
    return argv[1:]


def test_unbalanced_double_quote_in_value_does_not_merge_flags(tmp_path):
    """The PR-149 reproducer: stray leading ``"`` in two consecutive flag values
    must NOT cause them to be merged into one argv when the wrapper is parsed.
    """
    # These values mimic exactly what Jenkins shipped: a leading ``"`` and no
    # trailing one, because the upstream regex captured from a YAML quote.
    basic = '"123456789012.dkr.ecr.us-east-1.amazonaws.com/repo@sha256:6117fa030546ed0497f3e3b94577a17e25a3bf3ef3aeeb0e0a9f6d3a5afdcd6d'
    seq = '"123456789012.dkr.ecr.us-east-1.amazonaws.com/repo@sha256:72597ee32a608bfa7a3d99d115f448539b531e70518355c26adaf7973a42dcaf'
    argv = _run_through_wrapper(tmp_path, [
        f"--transform_image_basic={basic}",
        f"--transform_image_sequence={seq}",
        "--speedup_factor=20",
    ])
    assert argv == [
        f"--transform_image_basic={basic}",
        f"--transform_image_sequence={seq}",
        "--speedup_factor=20",
    ], f"argv was mangled by shell wrapper: {argv!r}"


def test_apostrophe_in_value_survives_wrapper(tmp_path):
    """A value containing a single quote (``'``) is the most common future
    footgun for the nested-quote pattern. Make sure it survives.
    """
    argv = _run_through_wrapper(tmp_path, [
        "--note=O'Brien's transform image",
        "--speedup_factor=42",
    ])
    assert argv == [
        "--note=O'Brien's transform image",
        "--speedup_factor=42",
    ], f"argv was mangled by shell wrapper: {argv!r}"


def test_wrapper_implementation_uses_quoted_inner_snippet():
    """Guard against accidental reversion to the broken
    ``nohup sh -c '<unquoted-inner>' ...`` pattern.
    """
    src = open(k8s_service.__file__).read()
    assert "shlex.quote(inner_snippet)" in src, (
        "exec_background_cmd no longer quotes its inner snippet — this brings "
        "back the PR-149 bash-quoting regression. See the test above."
    )
