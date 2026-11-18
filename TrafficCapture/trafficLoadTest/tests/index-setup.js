// SPDX-License-Identifier: Apache-2.0
//
// setup()'s index-probe rules, on fixed inputs and no network. They decide whether load may start.
// Assertions run in setup(): a throw in the default function leaves k6 exiting 0.

import { lookupSucceeded, requireIndexReady, settled } from '../lib/index-setup.js';

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}: expected '${expected}', got '${actual}'`);
  }
}

function assertThrows(fn, fragment, label) {
  let message = null;
  try {
    fn();
  } catch (e) {
    message = e.message;
  }
  if (message === null) {
    throw new Error(`${label}: expected a throw, got none`);
  }
  if (message.indexOf(fragment) < 0) {
    throw new Error(`${label}: expected message to contain '${fragment}', got '${message}'`);
  }
}

export function setup() {
  // Status 0 and 5xx mean "not serving yet", so neither may end the probe.
  assertEqual(settled({ status: 0 }), false, 'transport failure is unsettled');
  assertEqual(settled({ status: 500 }), false, '500 is unsettled');
  assertEqual(settled({ status: 503 }), false, '503 is unsettled');

  assertEqual(settled({ status: 200 }), true, '200 is settled');
  assertEqual(settled({ status: 404 }), true, '404 is settled');
  assertEqual(settled({ status: 401 }), true, '401 is settled');

  // Reading a refusal as "it exists" would skip creation and run the load at a missing index.
  assertEqual(lookupSucceeded({ status: 200 }), true, '200 is a successful lookup');
  assertEqual(lookupSucceeded({ status: 404 }), false, '404 is not a successful lookup');
  assertEqual(lookupSucceeded({ status: 401 }), false, '401 is not a successful lookup');
  assertEqual(lookupSucceeded({ status: 403 }), false, '403 is not a successful lookup');
  assertEqual(lookupSucceeded({ status: 429 }), false, '429 is not a successful lookup');

  // A failed k6 check never stops a run, so preparation failures have to throw.
  assertThrows(
    () => requireIndexReady('nyc_taxis', {
      ready: false, writable: false, status: 403, reason: "lookup of 'nyc_taxis' was refused",
    }),
    'was refused',
    'refused lookup aborts setup',
  );
  assertThrows(
    () => requireIndexReady('nyc_taxis', {
      ready: true, writable: false, status: 200,
      reason: "'nyc_taxis' did not reach yellow within 30s",
    }),
    'did not reach yellow',
    'readiness timeout aborts setup',
  );
  assertThrows(
    () => requireIndexReady('nyc_taxis', {
      ready: false, writable: false, status: 400, reason: "could not create index 'nyc_taxis'",
      body: 'mapper_parsing_exception',
    }),
    'mapper_parsing_exception',
    'a create failure reports the server body',
  );

  requireIndexReady('nyc_taxis', { ready: true, writable: true, status: 200 });
}

export default function () {}
