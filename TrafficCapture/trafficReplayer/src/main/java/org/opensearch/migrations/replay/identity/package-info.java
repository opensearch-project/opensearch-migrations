/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * Process-local value types that make replay ownership and correlation boundaries explicit.
 *
 * <p>The capture protocol identifies writers, connections, and request order, while Kafka supplies topics,
 * partitions, and offsets. Replay adds local ownership generations and lifetime sequences so work that
 * overlaps across expiration or a rebalance cannot accidentally compare equal. These types compose those
 * dimensions according to the architectural boundary being named instead of passing loose strings and
 * numbers between state machines.</p>
 *
 * <p>None of the local generation, sequence, batch-request, or deadline values are serialized into captured
 * traffic. Construction rejects missing components and invalid sequences immediately, preventing malformed
 * identities from becoming plausible map keys and corrupting ownership or completion accounting later.</p>
 */
package org.opensearch.migrations.replay.identity;
