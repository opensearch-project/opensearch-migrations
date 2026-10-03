"""Detection of migration workflows blocked on an unapproved approval gate.

Any gate a workflow is actively blocked on will block until someone approves it.
Two kinds reach that state:

- Runtime (`<kind>.<name>.vapretry`): a ValidatingAdmissionPolicy rejected a
  resource apply, so the workflow parks — `tryApply` carries
  `continueOn: {failed: true}` and `waitForFix` waits on the gate
  (`resourceManagement.ts`, `reconcile*Resource`). That is deliberate:
  `K8S_USER_APPROVAL_WAIT_RETRY_STRATEGY` is documented as "intentionally
  open-ended in elapsed time", sibling work keeps running, and the operator
  recovers in place with `workflow reset <resource>` + `workflow approve`.
- Step: a user-defined approval point from the migration config.

For an integration test, nobody is going to approve anything. The workflow stays
`Running` forever, so a test that only watches for ENDING_ARGO_PHASES can do
nothing but burn its whole timeout and then report a TimeoutError that says
nothing about which gate it was stuck on. Step gates are no better off than
runtime ones here: tests run `skipApprovals=true` (so no step gates exist at
all, and one waiting is unexpected by construction), except Test0003, which
approves a fixed list by name — a gate outside that list hangs just as hard.

So *any* gate blocking a wait is a test failure. Tests that intend to sit on a
gate name it (`IntegrationTestArgoService.expected_parked_gate_names`).

Note on the detection signal: the ApprovalGate CRD's status carries only `phase`
(one of Created/Pending/Approved/Error — see `generateMigrationResources.ts`
`statusSchemaFor`), and no denial reason. A gate sitting at `Pending` is also the
normal steady state for every gate the workflow has not reached yet, and gates
are pre-created at `Created` for the whole run, so gate phase alone cannot tell
"blocking now" from "not reached yet". `Error` would be the closest thing to a
denial signal, but nothing ever writes it: every write to a gate's phase is
`Created` (initializer), `Pending` (`resetGate`), or `Approved` (`approve.py`) —
`patchCaptureProxyError` sets the *CaptureProxy's* phase, not a gate's. (And
`waitForUserApproval` has no `failureCondition`, so a gate that somehow reached
`Error` would hang exactly like one at `Pending`.)

The authoritative signal is therefore the workflow node graph: an approval node
in `Running`, plus — for a runtime gate — a sibling `tryApply` in `Failed` under
the same boundaryID carrying the denial reason. `console_link`'s approve command
already extracts exactly that, so this module reuses it via `waiting_gates`
rather than reimplementing the traversal.

"Parked" throughout this module means "blocked on an approval nobody in this test
run is going to give", whatever the gate's kind.
"""

import logging
import time
from typing import Dict, Iterable, List, Optional, Sequence

from console_link.workflow.commands.approve import waiting_gates
from console_link.workflow.models.utils import load_k8s_config

logger = logging.getLogger(__name__)

# The inner workflow submitted by configureAndSubmitWorkflow.sh. Test workflows
# submit an outer wrapper whose own nodes are just configure/monitor/evaluate;
# the reconcile steps that can park live in this one.
INNER_WORKFLOW_NAME = "migration-workflow"

# How often to look for parked gates from inside a wait loop. The wait loops poll
# workflow status every few seconds; parking is not a state that needs sub-minute
# detection, so check less often than that to keep API traffic down.
DEFAULT_CHECK_INTERVAL_SECONDS = 30

# Require the same gate to be seen parked on two consecutive checks before
# failing. A single observation could in principle catch a `Failed` tryApply
# whose retryLoop iteration is about to move on, or a step gate that the test is
# about to approve; making the test fail on a transient would be worse than
# taking one extra interval to be sure.
DEFAULT_CONSECUTIVE_OBSERVATIONS = 2


class ParkedApprovalGateError(AssertionError):
    """A workflow is blocked on an approval gate no one will approve.

    AssertionError so pytest renders it as a test failure rather than an
    infrastructure error — the workflow behaved correctly; the migration config
    or the test's expectations are what's wrong.
    """


class ParkedGate:
    """A gate the workflow is actively blocked on.

    name     — ApprovalGate CRD name, e.g. 'datasnapshot.source1-testsnapshot.vapretry'
    reason   — VAP denial reason parsed off the failed tryApply sibling, or None
               (always None for a step gate; nothing failed to produce one)
    kind     — 'retry' for an Impossible-field denial (needs delete+recreate),
               'change' for a Gated-field denial (approval alone suffices),
               'step'  for a user-defined approval point from the config
    """

    __slots__ = ("name", "reason", "kind")

    def __init__(self, name: str, reason: Optional[str], kind: str):
        self.name = name
        self.reason = reason
        self.kind = kind

    def __str__(self) -> str:
        if self.kind == "step":
            # No apply failed, so there is usually nothing to quote here.
            detail = self.reason or "waiting for a user approval the test never gives"
        else:
            detail = self.reason or "<no denial reason on the failed apply>"
        return f"{self.name} ({self.kind}): {detail}"

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"ParkedGate(name={self.name!r}, reason={self.reason!r}, kind={self.kind!r})"


def find_parked_gates(
    namespace: str,
    workflow_name: str = INNER_WORKFLOW_NAME,
    expected_gate_names: Iterable[str] = (),
) -> List[ParkedGate]:
    """Return every gate `workflow_name` is currently blocked on, minus the expected ones.

    Runtime (`.vapretry`) and step gates both count: neither will clear without
    an approval, so both hang the wait. A test that approves its own step gates
    (or otherwise means to sit on one) passes those names in
    `expected_gate_names`.

    Never raises — a workflow that is missing, still starting, or has
    unreadable status yields an empty list, so a poll loop calling this can't
    fail for a reason unrelated to what it is checking.
    """
    expected = set(expected_gate_names)
    try:
        load_k8s_config()
        waiting = waiting_gates(namespace, workflow_name)
    except Exception as e:  # noqa: BLE001 — detection must never break the caller
        logger.debug("Could not check %s for parked gates: %s", workflow_name, e)
        return []

    parked = []
    for gate_name, reason, kind in waiting:
        if gate_name in expected:
            logger.info("Ignoring expected parked gate %s on workflow %s", gate_name, workflow_name)
            continue
        parked.append(ParkedGate(gate_name, reason, kind))
    return parked


def format_parked_gate_failure(parked: Dict[str, List[ParkedGate]]) -> str:
    """Build the failure message, including how to recover by hand."""
    all_gates = [gate for gates in parked.values() for gate in gates]
    workflows = ", ".join(name for name, gates in parked.items() if gates)
    lines = [
        f"Workflow(s) {workflows} are blocked on {len(all_gates)} unapproved "
        f"approval gate(s) and will not reach a terminal phase on their own:",
    ]
    lines.extend(f"  - {gate}" for gate in all_gates)

    if any(gate.kind == "retry" for gate in all_gates):
        lines.append(
            "An 'Impossible' denial means the field cannot be changed on the existing "
            "resource. Recovery is 'workflow reset <resource>' to delete it, then "
            "'workflow approve retry <gate>' to let the workflow re-create it."
        )
    if any(gate.kind == "change" for gate in all_gates):
        lines.append("A 'Gated' denial only needs 'workflow approve change <gate>'.")
    if any(gate.kind == "step" for gate in all_gates):
        lines.append(
            "A step gate is a user approval point from the migration config, cleared with "
            "'workflow approve step <gate>'. Tests normally run with skipApprovals=true, so "
            "one waiting here usually means the config or the workflow parameters created a "
            "gate the test does not know to approve."
        )
    lines.append(
        "Blocking on an unapproved gate is intended workflow behavior. If this test means to "
        "exercise it, add the gate name to IntegrationTestArgoService.expected_parked_gate_names."
    )
    return "\n".join(lines)


def summarize_parked_gates(parked: Dict[str, List[ParkedGate]]) -> str:
    """One-line summary, for enriching an otherwise-unrelated timeout message."""
    return "; ".join(
        f"{workflow_name}: [" + ", ".join(str(gate) for gate in gates) + "]"
        for workflow_name, gates in parked.items()
        if gates
    )


class ParkedGateWatcher:
    """Throttled, confirm-before-failing parked-gate check for a poll loop.

    Wait loops call `check()` on every iteration; the watcher decides when to
    actually hit the API and when an observation has persisted long enough to be
    real. `last_seen` keeps whatever was found so a timeout raised for some other
    reason can still report it.
    """

    def __init__(
        self,
        namespace: str,
        workflow_names: Sequence[str],
        expected_gate_names: Iterable[str] = (),
        check_interval_seconds: Optional[int] = None,
        required_consecutive_observations: Optional[int] = None,
        clock=time.monotonic,
    ):
        # dict.fromkeys rather than set(): callers pass (outer, inner) and the
        # outer name is the more useful one to name first in a failure message.
        self.workflow_names = list(dict.fromkeys(name for name in workflow_names if name))
        self.namespace = namespace
        self.expected_gate_names = set(expected_gate_names)
        # Resolved here rather than as parameter defaults so the module constants
        # stay patchable by tests that fast-forward a wait loop.
        self.check_interval_seconds = (
            DEFAULT_CHECK_INTERVAL_SECONDS if check_interval_seconds is None else check_interval_seconds)
        self.required_consecutive_observations = (
            DEFAULT_CONSECUTIVE_OBSERVATIONS if required_consecutive_observations is None
            else required_consecutive_observations)
        self._clock = clock
        self._next_check_at: Optional[float] = None
        self._consecutive_observations: Dict[str, int] = {}
        self.last_seen: Dict[str, List[ParkedGate]] = {}

    def check(self) -> None:
        """Raise ParkedApprovalGateError if a parked gate has persisted.

        A no-op until `check_interval_seconds` has elapsed since the last real
        check, so it is safe to call from a loop that polls far more often.
        """
        now = self._clock()
        if self._next_check_at is not None and now < self._next_check_at:
            return
        self._next_check_at = now + self.check_interval_seconds

        found: Dict[str, List[ParkedGate]] = {}
        for workflow_name in self.workflow_names:
            gates = find_parked_gates(self.namespace, workflow_name, self.expected_gate_names)
            if gates:
                found[workflow_name] = gates
        self.last_seen = found

        confirmed: Dict[str, List[ParkedGate]] = {}
        still_parked = set()
        for workflow_name, gates in found.items():
            confirmed_gates = []
            for gate in gates:
                key = f"{workflow_name}/{gate.name}"
                still_parked.add(key)
                count = self._consecutive_observations.get(key, 0) + 1
                self._consecutive_observations[key] = count
                if count >= self.required_consecutive_observations:
                    confirmed_gates.append(gate)
                else:
                    logger.warning(
                        "Workflow %s may be parked on approval gate %s (%s); confirming on the next check",
                        workflow_name, gate.name, gate.reason or "<no denial reason>",
                    )
            if confirmed_gates:
                confirmed[workflow_name] = confirmed_gates

        # Drop counts for gates that are no longer parked, so an intermittent
        # observation can't accumulate into a failure across unrelated checks.
        for key in list(self._consecutive_observations):
            if key not in still_parked:
                del self._consecutive_observations[key]

        if confirmed:
            raise ParkedApprovalGateError(format_parked_gate_failure(confirmed))

    def summarize_last_seen(self) -> str:
        return summarize_parked_gates(self.last_seen)
