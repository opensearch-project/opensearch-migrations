/**
 * Chaos control hooks.
 *
 * Allows an external orchestration layer to pause, resume, or throttle traffic
 * mid-test by writing a command string to a Redis key via the Webdis HTTP sidecar.
 *
 * Disabled by default (CONTROL_ENABLED != "true"). When disabled, checkControl()
 * returns true immediately — zero Webdis calls, zero overhead per iteration.
 *
 * Commands (written by the orchestration layer to the Redis key named by
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
 * How to send commands from the host while k6 is running inside Docker
 * (Webdis is exposed on host port 7379):
 *
 *   # pause all VUs:
 *   curl -s "http://localhost:7379/SET/control_cmd/pause"
 *
 *   # resume (two equivalent approaches):
 *   curl -s "http://localhost:7379/SET/control_cmd/resume"
 *   curl -s "http://localhost:7379/DEL/control_cmd"
 *
 *   # throttle to ~10 req/s (with baseRate=50, skips 80% of iterations):
 *   curl -s "http://localhost:7379/SET/control_cmd/set-rate%3A10"
 *
 *   # clear throttle:
 *   curl -s "http://localhost:7379/DEL/control_cmd"
 *
 */

import http from 'k6/http';
import { sleep } from 'k6';

const ENABLED    = (__ENV.CONTROL_ENABLED || 'false').toLowerCase() === 'true';
const WEBDIS_URL = __ENV.WEBDIS_URL      || 'http://webdis:7379';
const CMD_KEY    = __ENV.CONTROL_CMD_KEY || 'control_cmd';

// Tag all control-poll requests distinctly so they don't inflate pipeline metrics.
const POLL_PARAMS = { tags: { name: 'control_poll' } };

/** Read the current command string from Redis via Webdis. Returns '' on any error. */
function readCmd() {
  const res = http.get(`${WEBDIS_URL}/GET/${CMD_KEY}`, POLL_PARAMS);
  if (res.status !== 200) return '';
  try {
    return JSON.parse(res.body).GET || '';
  } catch (_) {
    return '';
  }
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
 * @returns {boolean} true → proceed with this iteration; false → skip it.
 */
export function checkControl(baseRate) {
  if (!ENABLED) return true;

  const cmd = readCmd();

  if (cmd === 'pause') {
    // Spin in 50 ms increments until the command is cleared or changed.
    // readCmd() returns '' on Webdis error, which exits the loop (fail open).
    while (readCmd() === 'pause') {
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
