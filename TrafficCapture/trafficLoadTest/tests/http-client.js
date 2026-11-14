// SPDX-License-Identifier: Apache-2.0

import { AUTH_MODE, withAuth } from '../lib/http-client.js';

export default function () {
  if (AUTH_MODE !== 'sigv4') {
    throw new Error(`expected SigV4 auth mode, got '${AUTH_MODE}'`);
  }

  let message = '';
  try {
    withAuth('GET', 'https://capture-proxy.example.com/_cluster/health', null, {});
  } catch (error) {
    message = String(error);
  }
  if (!message.includes('SigV4 authentication requires AWS access-key credentials')) {
    throw new Error(`expected request-time credential validation, got '${message}'`);
  }
}
