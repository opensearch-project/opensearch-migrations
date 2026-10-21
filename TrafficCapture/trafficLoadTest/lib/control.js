/**
 * Chaos control hooks.
 *
 * Allows an external orchestration layer to pause, resume, or throttle traffic
 * mid-test by writing a command string to a Valkey key.
 *
 * Disabled by default (CONTROL_ENABLED != "true"). When disabled, checkControl()
 * returns true immediately — zero Valkey calls, zero overhead per iteration.
 *
 * Commands (written by the orchestration layer to the Valkey key named by
 * CONTROL_CMD_KEY, default "control_cmd"):
 *
 *   pause        — VU enters a 50 ms sleep loop until the command is no longer
 *                  "pause". All VUs halt within one polling interval (~50 ms).
 *   resume       — clears a pause; VU continues normally. Any value other than
 *                  "pause" or a set-rate prefix also acts as a resume.
 *   set-rate:N   — VUs skip iterations with probability (1 - N / baseRate) so
 *                  effective throughput ≈ N/baseRate × configured rate. Coarse-
 *                  grained: works best for ≥20% reductions at the configured rate.
 *
 * How to send commands from a host with kubectl access:
 *
 *   # pause all VUs:
 *   kubectl exec deploy/valkey -- valkey-cli SET control_cmd pause
 *
 *   # resume (two equivalent approaches):
 *   kubectl exec deploy/valkey -- valkey-cli SET control_cmd resume
 *   kubectl exec deploy/valkey -- valkey-cli DEL control_cmd
 *
 *   # throttle to ~10 req/s (with baseRate=50, skips 80% of iterations):
 *   kubectl exec deploy/valkey -- valkey-cli SET control_cmd set-rate:10
 *
 *   # clear throttle:
 *   kubectl exec deploy/valkey -- valkey-cli DEL control_cmd
 *
 */

import { sleep } from 'k6';
import { CFG } from './config.js';
import { valkeyCommand } from './valkey.js';

const ENABLED    = (CFG.CONTROL_ENABLED || 'false').toLowerCase() === 'true';
const CMD_KEY    = CFG.CONTROL_CMD_KEY || 'control_cmd';

/** Read the current command string from Valkey. Returns '' on any error. */
async function readCmd() {
  return (await valkeyCommand('GET', CMD_KEY)) || '';
}

/**
 * Check for control commands between VU iterations.
 *
 * Call at the top of every VU function. Returns false when the orchestration
 * layer wants this iteration skipped; the caller must `return` immediately.
 *
 * @param {number} baseRate - the scenario's configured req/s target; used to
 *   compute the skip probability for set-rate throttling. Pass 0 to disable
 *   throttling (pause/resume still work).
 * @returns {Promise<boolean>} true → proceed with this iteration; false → skip it.
 */
export async function checkControl(baseRate) {
  if (!ENABLED) return true;

  const cmd = await readCmd();

  if (cmd === 'pause') {
    // Spin in 50 ms increments until the command is cleared or changed.
    // readCmd() returns '' on Valkey error, which exits the loop (fail open).
    while (await readCmd() === 'pause') {
      sleep(0.05);
    }
    return true; // proceed normally after resume
  }

  if (cmd.startsWith('set-rate:')) {
    const targetRate = parseInt(cmd.slice(9));
    if (!isNaN(targetRate) && baseRate > 0 && targetRate < baseRate) {
      if (Math.random() < 1 - targetRate / baseRate) return false;
    }
  }

  return true;
}
