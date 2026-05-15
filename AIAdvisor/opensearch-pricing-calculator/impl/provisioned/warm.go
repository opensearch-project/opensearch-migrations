// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/instances"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"strings"
)

// UltraWarm cache sizes in GB
const (
	uwMediumCacheSize = 1.5 * 1024 // 1536 GB (1.5 TB) - ultrawarm1.medium.search
	uwLargeCacheSize  = 20 * 1024  // 20480 GB (20 TB) - ultrawarm1.large.search
)

// AllWarmInstanceTypes is the full list of warm instance types evaluated during auto-selection.
// UltraWarm types are always included. OI2 types are filtered out at evaluation time
// for non-OpenSearch-Optimized hot nodes via the isOI2WarmInstance guard.
var AllWarmInstanceTypes = []string{
	"ultrawarm1.medium.search",
	"ultrawarm1.large.search",
	"oi2.large.search",
	"oi2.xlarge.search",
	"oi2.2xlarge.search",
	"oi2.4xlarge.search",
	"oi2.8xlarge.search",
}

// isOI2WarmInstance checks if the warm instance type is a valid OI2 warm instance.
// Only instances defined in the centralized OI2WarmInstanceLimitsMap are considered valid.
// OI2 warm instances can only be used with OpenSearch Optimized hot nodes.
func isOI2WarmInstance(instanceType string) bool {
	return instances.IsOI2WarmInstance(instanceType)
}

// isOpenSearchOptimizedInstance checks if the instance type is OpenSearch Optimized family.
// OI2 warm instances can only be used when hot nodes are OpenSearch Optimized instances (OR1, OR2, OM2, OI2).
func isOpenSearchOptimizedInstance(instanceType string) bool {
	instanceLower := strings.ToLower(instanceType)
	prefixes := []string{"or1.", "or2.", "om2.", "oi2."}
	for _, prefix := range prefixes {
		if strings.HasPrefix(instanceLower, prefix) {
			return true
		}
	}
	return false
}

// getWarmInstanceCacheSize returns the max addressable warm storage in GB for a given warm instance type.
// This is the amount of warm data each node can hold.
//
// For UltraWarm instances: returns the cache size (1.5 TB or 20 TB)
// For OI2 instances: returns the max addressable warm storage (5× cache size)
//   - Per AWS docs: Cache = 80% of NVMe storage, Max addressable = 5× cache
//   - Example: oi2.large has 468 GB NVMe → 375 GB cache → 1875 GB max addressable
//
// The hotInstances parameter is kept for backward compatibility but OI2 values
// are now sourced from the centralized instances.OI2WarmInstanceLimitsMap.
func getWarmInstanceCacheSize(warmInstanceType string, hotInstances map[string]price.InstanceUnit) float64 {
	// Check if it's an OI2 instance - use centralized limits
	if instances.IsOI2WarmInstance(warmInstanceType) {
		// Return max addressable warm storage (5× cache) for node count calculations
		return instances.GetOI2MaxAddressableStorage(warmInstanceType)
	}

	// UltraWarm cache sizes
	if strings.Contains(warmInstanceType, "medium") {
		return uwMediumCacheSize // 1536 GB (1.5 TB)
	} else if strings.Contains(warmInstanceType, "large") {
		return uwLargeCacheSize // 20480 GB (20 TB)
	}

	return 0 // Unknown instance type
}
