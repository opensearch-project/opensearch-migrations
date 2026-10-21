"""`loadtest` — submit and manage k6 load-test runs.

Bare `loadtest` opens the load-test TUI (tui/app.py); the subcommands below are the non-interactive
equivalents. Both go through the same helpers in runs.py, so neither can drift.

Nothing probes the cluster before doing its work. A command that cannot run fails on the real call,
which reports what actually went wrong; only when the result is a dead end does the command ask
whether the chart is installed and add the install hint. See runs.chart_missing_hint for why.
"""

import logging
import signal
import subprocess
import sys

import click
from click.shell_completion import get_completion_class

# Restore default SIGPIPE handling so piped commands (e.g. `| head`) exit cleanly.
try:
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)
except (AttributeError, ValueError):
    pass

from .utils import ExitCode, load_k8s_config, get_current_namespace          # noqa: E402
from . import runs                                                          # noqa: E402
from .health import HealthWatcher, format_health, run_health, threshold_warning   # noqa: E402
from .runs import (                                                         # noqa: E402
    SUCCESS_PHASE,
    build_k6_parameters,
    chart_missing_hint,
    k6_workflows,
    list_runs,
    logs_command,
    submit_k6_run,
    wait_for_run,
)
from .testrun_utils import delete_workflow, list_profiles, list_scenarios    # noqa: E402

logger = logging.getLogger(__name__)

HELP_CONTEXT = {'help_option_names': ['-h', '--help']}

_NAMESPACE_OPTION = dict(default=get_current_namespace, hidden=True, envvar='LOADTEST_NAMESPACE')


# ---------------------------------------------------------------------------
# Shell completion — dynamic values from the cluster, static fallback when offline.
# ---------------------------------------------------------------------------
def _cluster_values(fetch, fallback):
    """Best-effort completion values from the cluster. Shell completion must stay fast and never
    raise, so any failure (no kubeconfig, chart not installed) falls back to the static hint list."""
    try:
        load_k8s_config()
        return fetch(get_current_namespace()) or fallback
    except Exception:
        return fallback


def _complete_scenarios(ctx, param, incomplete):
    return [v for v in _cluster_values(list_scenarios, runs.SCENARIOS) if v.startswith(incomplete)]


def _complete_profiles(ctx, param, incomplete):
    return [v for v in _cluster_values(list_profiles, runs.PROFILES) if v.startswith(incomplete)]


# ---------------------------------------------------------------------------
# Dead-end diagnosis.
# ---------------------------------------------------------------------------
def _exit_if_chart_missing(ctx, namespace):
    """On a dead end, say whether the chart is missing and stop. This is the only diagnosis the
    caller gets, so a failure to answer must surface as itself rather than as "not installed"."""
    hint = chart_missing_hint(namespace)
    if hint:
        click.echo(hint, err=True)
        ctx.exit(ExitCode.FAILURE.value)


def _echo_hint_best_effort(namespace):
    """Add the install hint after a real error is already on screen. The error is the diagnosis
    here, so a failure to produce the hint just means no hint — it must not mask what was shown.

    The k8s client retries a failed call and logs a warning per attempt. The user has already been
    told what went wrong, so those warnings would only bury it; they are muted for the lookup.
    Muting has to be `logging.disable`, which is checked when a record is emitted: raising the
    urllib3 logger's level does not hold, because constructing the API client rebuilds the
    kubernetes Configuration, which sets that logger's level itself. ERROR and above still pass.
    """
    previous = logging.root.manager.disable
    logging.disable(logging.WARNING)
    try:
        hint = chart_missing_hint(namespace)
    except Exception as e:
        # Restored here, before the debug line, so muting the retries does not also mute the one
        # record that explains why there is no hint. The `finally` below then repeats it harmlessly.
        logging.disable(previous)
        logger.debug("Could not check for the k6LoadTest chart: %s", e)
        return
    finally:
        logging.disable(previous)
    if hint:
        click.echo(hint, err=True)


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------
@click.group(invoke_without_command=True, context_settings=HELP_CONTEXT)
@click.option('-v', '--verbose', count=True, help="Verbosity level. Default is warn, -v is info, -vv is debug.")
@click.option('--namespace', **_NAMESPACE_OPTION)
@click.option('--refresh-interval', default=5.0, type=float, hidden=True,
              help="Seconds between run-table refreshes in the TUI.")
@click.pass_context
def loadtest_cli(ctx, verbose, namespace, refresh_interval):
    """Submit and manage k6 load-test runs (k6-operator TestRuns).

    With no subcommand this opens the load-test TUI: a live table of runs, plus launch, stop and
    log viewing.
    """
    # Configure logging - only if no handlers exist to avoid issues with Click's CliRunner in tests
    root_logger = logging.getLogger()
    if not root_logger.handlers:
        logging.basicConfig(level=logging.WARN - (10 * verbose))
    else:
        root_logger.setLevel(logging.WARN - (10 * verbose))

    if ctx.invoked_subcommand is not None:
        return

    try:
        load_k8s_config()
        # A load-test TUI with no chart can do nothing at all — no scenarios to launch and no runs
        # to show — so say why and stop rather than opening a dead screen.
        _exit_if_chart_missing(ctx, namespace)
    except click.exceptions.Exit:
        raise
    except Exception as e:
        click.echo(f"Error: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)

    # Imported here so the subcommands (and shell completion) never pay for Textual.
    from .tui.app import LoadTestApp
    try:
        LoadTestApp(namespace, refresh_interval=refresh_interval).run()
    except Exception as e:
        click.echo(f"Error: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)


@loadtest_cli.command(name="run")
@click.option('--scenario', default=None, shell_complete=_complete_scenarios,
              help="Scenario to run, which selects its steady profile (default: ingest). "
                   "Redundant with --config, which names the scenario itself.")
@click.option('--config', 'config_name', default=None, shell_complete=_complete_profiles,
              help="Load profile to run — a WorkflowTemplate (default: <scenario>-steady).")
@click.option('--parallelism', default=1, type=int, show_default=True,
              help="Number of runner pods. k6 splits the load across them via execution segments, "
                   "so --rate/--vus are GLOBAL totals divided among runners.")
@click.option('--target', 'target_url', default=None,
              help="Capture Proxy URL, e.g. https://<proxy>.ma.svc.cluster.local:9200. "
                   "If omitted, the chart's captureProxyUrl is used.")
@click.option('--rate', default=None, help="Override request rate (INGEST_RATE/SEARCH_RATE).")
@click.option('--duration', default=None, help="Override DURATION (e.g. 5m, 30s).")
@click.option('--vus', default=None, help="Override pre-allocated VUs.")
@click.option('--registry-enabled/--no-registry-enabled', 'registry_enabled', default=None,
              help="Force the mixed/consistency ring buffer on/off (default: keep the profile's).")
@click.option('--control-enabled/--no-control-enabled', 'control_enabled', default=None,
              help="Force the chaos control bus on/off (default: keep the profile's).")
@click.option('--override', '-e', 'overrides', multiple=True, metavar='KEY=VALUE',
              help="Override any other setting of the profile by name (matches k6-run.sh's -e). "
                   "Repeatable. A name the profile does not have is an error, not a no-op.")
@click.option('--auth-secret-name', default=None,
              help="Kubernetes Secret imported into runner environment variables.")
@click.option('--extra-args', default=None, help="Extra flags for `k6 run` (e.g. --no-thresholds).")
@click.option('--namespace', **_NAMESPACE_OPTION)
@click.option('--wait', is_flag=True, default=False, help="Wait for the run to complete.")
@click.option('--timeout', default=600, type=int, help="Seconds to wait with --wait (default 600).")
@click.option('--wait-interval', default=5, type=int, help="Seconds between status checks with --wait.")
@click.option('--health/--no-health', default=True, show_default=True,
              help="While waiting, report each poll's request counts and error rate, read from "
                   "the runners' own k6 API. Turn it off for phase-only output.")
@click.pass_context
def k6_run(ctx, namespace, wait, timeout, wait_interval, health, **run_opts):
    """Submit a k6 run.

    A run is one load profile, with any of its settings overridden by name. `loadtest status` lists
    the profiles the installed chart offers; `kubectl get workflowtemplate k6-<profile> -o yaml`
    shows every setting one has, and its value.

    \b
    Examples:
      loadtest run --scenario ingest --target https://my-proxy:9200
      loadtest run --config search-deep-paging --rate 100 --duration 10m
      loadtest run --config mixed-burst --parallelism 4 -e BULK_BATCH_SIZE=50
    """
    # Every remaining option names a build_k6_parameters keyword (that's why the click options
    # above spell out `config_name`/`target_url`), so the run spec is assembled by forwarding the
    # bag wholesale. `overrides` is the one exception: it arrives as a repeated-flag tuple and has
    # to be flattened into the newline-delimited text that build_k6_parameters expects.
    overrides = run_opts.pop("overrides", ())
    try:
        params = build_k6_parameters(
            overrides_text=("\n".join(overrides) if overrides else None), **run_opts)
    except ValueError as e:
        click.echo(f"Error: --override {e}", err=True)
        ctx.exit(ExitCode.INVALID_INPUT.value)
        return
    target_url = params["targetUrl"]
    config_name = params["configName"]

    try:
        load_k8s_config()
        name = submit_k6_run(namespace, params)
    except ValueError as e:
        # A missing WorkflowTemplate or a setting the profile does not have is an unmet
        # precondition, not bad input: the message already names what the cluster does offer.
        click.echo(f"Error: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return
    except Exception as e:
        click.echo(f"Error submitting k6 run: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    click.echo(f"Submitted k6 run: {name}")
    target_note = f" target={target_url}" if target_url else ""
    click.echo(f"  profile={config_name} parallelism={params['parallelism']}{target_note}")
    click.echo(f"\nWatch:  loadtest list\n        loadtest logs {name} -f")

    if wait:
        click.echo(f"\nWaiting for completion (timeout {timeout}s)...")
        # The watcher is what makes the end of the wait readable: the k6 API dies with the runner
        # pods, so the last poll to reach them, and every threshold crossed along the way, exist
        # only in here by the time the phase turns terminal.
        watcher = HealthWatcher(namespace, name) if health else None

        def report(elapsed, _phase):
            click.echo("  " + format_health(watcher.poll(elapsed)))

        phase = wait_for_run(namespace, name, timeout, wait_interval,
                             on_poll=report if watcher else None)
        if phase is None:
            click.echo(f"Timed out after {timeout}s waiting for {name}.", err=True)
            _echo_run_health_verdict(watcher, name)
            ctx.exit(ExitCode.FAILURE.value)
        click.echo(f"Run {name} finished: {phase}")
        _echo_run_health_verdict(watcher, name)
        if phase != SUCCESS_PHASE:
            ctx.exit(ExitCode.FAILURE.value)


def _echo_run_health_verdict(watcher, name):
    """Close a wait with what k6 measured: the last totals, then any threshold warning.

    A crossed threshold does NOT change the exit code. The phase keeps deciding that, because the
    thresholds are per-scenario limits rather than a definition of failure, and a script that must
    fail on one can read the warning or the k6 summary in the logs.
    """
    if watcher is None:
        return
    if watcher.last:
        last = watcher.last
        click.echo(f"  Last seen: {last['requests']} requests, {last['ok']} ok, "
                   f"{last['failed']} failed ({last['failed_pct']:.1f}%)")
    # The verdict comes from the runner pods' exit codes, which outlive the k6 API the wait was
    # polling. The last poll's taints only supply the metric names, since an exit code has none.
    warning = threshold_warning(watcher.verdict(), name, watcher.last_tainted)
    if warning:
        # The warning goes to stderr, and stdout is block-buffered whenever this is not a terminal
        # (`kubectl exec`, a CI log). Without the flush the two streams interleave and the warning
        # lands ABOVE the phase line and the totals it is qualifying.
        sys.stdout.flush()
        click.echo(warning, err=True)


@loadtest_cli.command(name="list")
@click.option('--scenario', default=None, shell_complete=_complete_scenarios,
              help="Filter by scenario.")
@click.option('--namespace', **_NAMESPACE_OPTION)
@click.pass_context
def k6_list(ctx, scenario, namespace):
    """List k6 runs."""
    try:
        load_k8s_config()
        found = list_runs(namespace, scenario)
    except Exception as e:
        click.echo(f"Error listing k6 runs: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    if not found:
        click.echo("No k6 runs found.")
        # "No runs" and "no load testing here" read the same, so make the difference explicit.
        _exit_if_chart_missing(ctx, namespace)
        return

    rows = [(r["name"], r["profile"], r["phase"], r["parallelism"], r["age"]) for r in found]

    header = ("NAME", "PROFILE", "STAGE", "PARALLEL", "AGE")
    widths = [max(len(str(r[i])) for r in (header, *rows)) for i in range(len(header))]
    click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(header)))
    for r in rows:
        click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(r)))


@loadtest_cli.command(name="stop")
@click.argument('name', required=False)
@click.option('--all', 'stop_all', is_flag=True, default=False, help="Stop all k6 runs.")
@click.option('--scenario', default=None, shell_complete=_complete_scenarios,
              help="Stop all k6 runs for one scenario.")
@click.option('--namespace', **_NAMESPACE_OPTION)
@click.pass_context
def k6_stop(ctx, name, stop_all, scenario, namespace):
    """Stop k6 run(s).

    Stopping a run deletes its Workflow; the TestRun is owned by it, so the CR and the operator's
    runner/initializer pods go with it (there is no graceful pause).

    \b
    Examples:
      loadtest stop k6-ingest-abc12
      loadtest stop --scenario mixed
      loadtest stop --all
    """
    if sum(bool(x) for x in (name, stop_all, scenario)) != 1:
        click.echo("Error: specify exactly one of NAME, --all, or --scenario.", err=True)
        ctx.exit(ExitCode.INVALID_INPUT.value)

    try:
        load_k8s_config()
        if name:
            names = [name]
        else:
            names = [wf.get("metadata", {}).get("name", "")
                     for wf in k6_workflows(namespace, scenario)]
            names = [n for n in names if n]
    except Exception as e:
        click.echo(f"Error resolving k6 runs: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    if not names:
        click.echo("No matching k6 runs.")
        _exit_if_chart_missing(ctx, namespace)
        return

    missing = 0
    for n in names:
        try:
            deleted = delete_workflow(namespace, n)
        except Exception as e:
            click.echo(f"{n}: stop failed: {e}", err=True)
            ctx.exit(ExitCode.FAILURE.value)
            return
        if deleted:
            click.echo(f"{n}: stopped")
        else:
            missing += 1
            click.echo(f"{n}: not found", err=True)
    if missing:
        ctx.exit(ExitCode.NOT_FOUND.value)


@loadtest_cli.command(name="logs")
@click.argument('name')
@click.option('-f', '--follow', is_flag=True, default=False, help="Stream live logs.")
@click.option('--namespace', **_NAMESPACE_OPTION)
@click.pass_context
def k6_logs(ctx, name, follow, namespace):
    """Show logs for a k6 run (the runner pods' k6 containers).

    \b
    Examples:
      loadtest logs k6-ingest-abc12
      loadtest logs k6-ingest-abc12 -f
    """
    code = subprocess.run(logs_command(namespace, name, follow)).returncode
    if code:
        # kubectl has already printed the real error — a missing chart shows up here as a Forbidden
        # on the pod read, because that grant ships with the chart. Add the hint, keep kubectl's code.
        _echo_hint_best_effort(namespace)
        ctx.exit(code)


@loadtest_cli.command(name="health")
@click.argument('name')
@click.option('--namespace', **_NAMESPACE_OPTION)
@click.pass_context
def k6_health(ctx, name, namespace):
    """Show what k6 measures right now for a RUNNING run: requests, errors, p95, thresholds.

    Read from each runner pod's own k6 REST API, so it needs no metrics stack. It works only while
    the run is alive — the API goes with the runner process, and the full end-of-test summary is
    then in `loadtest logs NAME`.

    A crossed threshold is reported, not enforced: this exits 0 either way.

    \b
    Examples:
      loadtest health k6-ingest-steady-abc12
    """
    try:
        load_k8s_config()
        health = run_health(namespace, name)
    except Exception as e:
        click.echo(f"Error reading k6 health: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    if health["error"]:
        click.echo(f"Error reading k6 health: {health['error']}", err=True)
        _echo_hint_best_effort(namespace)
        ctx.exit(ExitCode.FAILURE.value)
        return
    if not health["expected"]:
        click.echo(f"No runner pods for '{name}' — the run has not started, or has finished "
                   f"and been torn down.")
        ctx.exit(ExitCode.NOT_FOUND.value)
        return

    click.echo(f"Run {name} — {health['reachable']}/{health['expected']} runners reporting")
    header = ("RUNNER", "VUS", "REQS", "OK", "ERR", "ERR%", "P95")
    rows = [(r["pod"], *_health_row(r)) for r in health["runners"]]
    if health["reachable"]:
        # The totals a run is judged on. p95 is the worst runner's, not a mean: percentiles from
        # separate pods do not combine, so the label says which pod's number this is.
        rows.append(("TOTAL", str(health["vus"]), str(health["requests"]), str(health["ok"]),
                     str(health["failed"]), f"{health['failed_pct']:.1f}%",
                     _p95(health["worst_p95_ms"]) + " (worst runner)"))
    widths = [max(len(str(r[i])) for r in (header, *rows)) for i in range(len(header))]
    click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(header)))
    for row in rows:
        click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(row)))

    if health["tainted"]:
        # A snapshot of a live run, so this says where the metrics stand NOW. k6 clears the flag
        # if they recover, and only its exit code at the end settles the run — see
        # health.threshold_warning.
        sys.stdout.flush()   # keep the warning below the table it refers to
        click.echo(f"\nWARNING: over threshold right now: {', '.join(health['tainted'])}\n"
                   f"         k6 clears this if the metric recovers; the run is judged on where\n"
                   f"         it stands at the end.", err=True)


def _health_row(runner):
    """One runner's cells, or dashes when its k6 API could not be reached."""
    if not runner.get("reachable"):
        return ("-", "-", "-", "-", "-", "unreachable")
    return (str(runner["vus"]), str(runner["requests"]), str(runner["ok"]), str(runner["failed"]),
            f"{100.0 * runner['failed'] / runner['requests']:.1f}%" if runner["requests"] else "-",
            _p95(runner["p95_ms"]))


def _p95(value):
    return f"{value:.0f}ms" if value is not None else "-"


@loadtest_cli.command(name="status")
@click.option('--namespace', **_NAMESPACE_OPTION)
@click.pass_context
def k6_status(ctx, namespace):
    """Report whether load testing is available here, and which profiles are launchable."""
    try:
        load_k8s_config()
        _exit_if_chart_missing(ctx, namespace)
        catalog = runs.profile_catalog(namespace)
    except click.exceptions.Exit:
        raise
    except Exception as e:
        click.echo(f"Error checking load-test availability: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    click.echo(f"k6LoadTest chart: installed in namespace '{namespace}'")
    if not catalog:
        click.echo("Profiles: none")
        return
    width = max(len(p) for p in catalog)
    click.echo("Profiles:")
    for profile in sorted(catalog):
        entry = catalog[profile]
        click.echo(f"  {profile.ljust(width)}  {entry['description'] or entry['scenario']}")


@loadtest_cli.group(name="util", context_settings=HELP_CONTEXT)
@click.pass_context
def util_group(ctx):
    """Utility commands"""


@util_group.command(name="completions")
@click.argument('shell', type=click.Choice(['bash', 'zsh', 'fish']))
@click.pass_context
def completion(ctx, shell):
    """Generate shell completion script for bash, zsh, or fish.

    Example setup:
      Bash: loadtest util completions bash > /etc/bash_completion.d/loadtest
      Zsh:  loadtest util completions zsh > "${fpath[1]}/_loadtest"
      Fish: loadtest util completions fish > ~/.config/fish/completions/loadtest.fish

    Restart your shell after installation.
    """
    completion_class = get_completion_class(shell)
    if completion_class is None:
        logger.error(f"{shell} shell is currently not supported")
        ctx.exit(ExitCode.INVALID_INPUT.value)

    try:
        completion_script = completion_class(lambda: loadtest_cli(ctx),
                                             {},
                                             "loadtest",
                                             "_LOADTEST_COMPLETE").source()
        click.echo(completion_script)
    except RuntimeError as exc:
        logger.error(f"Failed to generate completion script: {exc}")
        ctx.exit(ExitCode.FAILURE.value)


def _enable_short_help(command):
    command.context_settings.setdefault('help_option_names', ['-h', '--help'])
    if isinstance(command, click.Group):
        for subcommand in command.commands.values():
            _enable_short_help(subcommand)


_enable_short_help(loadtest_cli)


def main():
    """Main entry point for the load-test CLI."""
    try:
        loadtest_cli()
    except Exception as e:
        logger.exception(e)
        sys.exit(ExitCode.FAILURE.value)


if __name__ == "__main__":
    main()
