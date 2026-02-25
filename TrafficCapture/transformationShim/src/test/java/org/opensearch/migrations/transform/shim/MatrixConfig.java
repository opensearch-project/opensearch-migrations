/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Default test matrix configuration, deserialized from {@code matrix.config.json}.
 * Test cases inherit these defaults unless they override with their own values.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record MatrixConfig(
    List<String> defaultSolrVersions,
    String defaultOpenSearchImage
) {}
