import redis from 'k6/x/redis';
import { CFG } from './config.js';

const client = new redis.Client(CFG.VALKEY_URL || 'redis://valkey:6379');

/**
 * Execute one RESP command against Valkey.
 *
 * The registry and control bus are optional test aids. Preserve their existing graceful-degradation
 * behavior when the store is unavailable instead of failing the traffic iteration.
 */
export async function valkeyCommand(command, ...args) {
  try {
    return await client.sendCommand(command, ...args);
  } catch (_) {
    return null;
  }
}
