import redis from 'k6/x/redis';
import { Counter } from 'k6/metrics';
import { CFG } from './config.js';

const client = new redis.Client(CFG.VALKEY_URL || 'redis://valkey:6379');
const commandErrors = new Counter('valkey_command_errors');
let warningEmitted = false;

function isMissingValue(error) {
  return String(error).includes('redis: nil');
}

/**
 * Execute one RESP command against Valkey.
 *
 * The registry and control bus are optional test aids. Preserve their existing graceful-degradation
 * behavior when the store is unavailable instead of failing the traffic iteration. Missing keys
 * are normal for GET and LINDEX. Other errors remain fail-open, but are counted and reported once
 * per VU so a broken optional feature is visible without flooding a load-test log.
 */
export async function valkeyCommand(command, ...args) {
  try {
    return await client.sendCommand(command, ...args);
  } catch (error) {
    if (!isMissingValue(error)) {
      commandErrors.add(1, { command });
      if (!warningEmitted) {
        console.warn(
          `Valkey command failed; optional registry/control operations will fail open: ${error}`,
        );
        warningEmitted = true;
      }
    }
    return null;
  }
}
