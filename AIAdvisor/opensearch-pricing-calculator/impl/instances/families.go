// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package instances

const OpenSearchOptimizedFamilyName = "OpenSearch Optimized"
const OpenSearchOptimized = "OR1"
const GeneralPurpose = "General purpose"
const MemoryOptimized = "Memory optimized"
const StorageOptimized = "Storage optimized"
const ComputeOptimized = "Compute optimized"

var FamilyPatterns = map[string]string{
	`^(t2|t3|m[3-9])`:                GeneralPurpose,
	`^r[3-9]`:                        MemoryOptimized,
	`^(i[2-9]|im4|i8ge)`:             StorageOptimized,
	`^c[3-9]`:                        ComputeOptimized,
	`^(or[12345]|om[2345]|oi[2345])`: OpenSearchOptimized,
}
