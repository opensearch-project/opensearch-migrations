// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package instances

import (
	"testing"
)

func TestGetOI2MaxAddressableStorage(t *testing.T) {
	tests := []struct {
		instanceType string
		expected     float64
	}{
		{"oi2.large.search", 1875},
		{"oi2.xlarge.search", 3750},
		{"oi2.2xlarge.search", 7500},
		{"oi2.4xlarge.search", 15000},
		{"oi2.8xlarge.search", 30000},
		{"oi2.unknown.search", 0},
		{"ultrawarm1.medium.search", 0},
	}

	for _, tt := range tests {
		t.Run(tt.instanceType, func(t *testing.T) {
			result := GetOI2MaxAddressableStorage(tt.instanceType)
			if result != tt.expected {
				t.Errorf("GetOI2MaxAddressableStorage(%q) = %v, want %v", tt.instanceType, result, tt.expected)
			}
		})
	}
}

func TestGetOI2CacheSize(t *testing.T) {
	tests := []struct {
		instanceType string
		expected     float64
	}{
		{"oi2.large.search", 375},
		{"oi2.xlarge.search", 750},
		{"oi2.2xlarge.search", 1500},
		{"oi2.4xlarge.search", 3000},
		{"oi2.8xlarge.search", 6000},
		{"oi2.unknown.search", 0},
		{"ultrawarm1.medium.search", 0},
	}

	for _, tt := range tests {
		t.Run(tt.instanceType, func(t *testing.T) {
			result := GetOI2CacheSize(tt.instanceType)
			if result != tt.expected {
				t.Errorf("GetOI2CacheSize(%q) = %v, want %v", tt.instanceType, result, tt.expected)
			}
		})
	}
}

func TestIsOI2WarmInstance(t *testing.T) {
	tests := []struct {
		instanceType string
		expected     bool
	}{
		{"oi2.large.search", true},
		{"oi2.xlarge.search", true},
		{"oi2.2xlarge.search", true},
		{"oi2.4xlarge.search", true},
		{"oi2.8xlarge.search", true},
		{"oi2.unknown.search", false}, // Not in the map
		{"ultrawarm1.medium.search", false},
		{"or1.xlarge.search", false},
	}

	for _, tt := range tests {
		t.Run(tt.instanceType, func(t *testing.T) {
			result := IsOI2WarmInstance(tt.instanceType)
			if result != tt.expected {
				t.Errorf("IsOI2WarmInstance(%q) = %v, want %v", tt.instanceType, result, tt.expected)
			}
		})
	}
}

func TestGetEffectiveMaxHotNodes(t *testing.T) {
	tests := []struct {
		name         string
		instanceType string
		azs          int
		expected     int
	}{
		// Per-AZ limit is lower than family limit (400)
		{"1 AZ r6g.xlarge (family=400)", "r6g.xlarge.search", 1, 334},
		{"2 AZs r6g.xlarge (family=400)", "r6g.xlarge.search", 2, 400},
		{"3 AZs r6g.xlarge (family=400)", "r6g.xlarge.search", 3, 400},

		// Family limit is lower than per-AZ limit
		{"1 AZ t3.small (family=10)", "t3.small.search", 1, 10},
		{"3 AZs m3.medium (family=200)", "m3.medium.search", 3, 200},

		// OR1 with 1002 family limit
		{"1 AZ or1.xlarge (family=1002)", "or1.xlarge.search", 1, 334},
		{"2 AZs or1.xlarge (family=1002)", "or1.xlarge.search", 2, 668},
		{"3 AZs or1.xlarge (family=1002)", "or1.xlarge.search", 3, 1002},

		// Unknown instance (no family limit defined)
		{"Unknown instance 1 AZ", "unknown.xlarge.search", 1, 334},
		{"Unknown instance 3 AZs", "unknown.xlarge.search", 3, 1002},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := GetEffectiveMaxHotNodes(tt.instanceType, tt.azs)
			if result != tt.expected {
				t.Errorf("GetEffectiveMaxHotNodes(%q, %d) = %d, want %d",
					tt.instanceType, tt.azs, result, tt.expected)
			}
		})
	}
}

func TestMaxHotNodesPerAZ(t *testing.T) {
	if MaxHotNodesPerAZ != 334 {
		t.Errorf("MaxHotNodesPerAZ = %d, want 334 (per AWS docs)", MaxHotNodesPerAZ)
	}
}

// --- IsStorageClassSupported tests ---

func TestIsStorageClassSupported(t *testing.T) {
	tests := []struct {
		name         string
		limits       InstanceLimits
		storageClass string
		expected     bool
	}{
		{"gp2 always supported", InstanceLimits{MaximumGp2: 1024}, "gp2", true},
		{"gp2 even with zero max", InstanceLimits{MaximumGp2: 0}, "gp2", true},
		{"gp3 supported when MaximumGp3 > 0", InstanceLimits{MaximumGp3: 2048}, "gp3", true},
		{"gp3 not supported when MaximumGp3 is 0", InstanceLimits{MaximumGp3: 0}, "gp3", false},
		{"unknown storage class", InstanceLimits{}, "io1", false},
		{"empty storage class", InstanceLimits{}, "", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := tt.limits.IsStorageClassSupported(tt.storageClass)
			if result != tt.expected {
				t.Errorf("IsStorageClassSupported(%q) = %v, want %v", tt.storageClass, result, tt.expected)
			}
		})
	}
}

// --- GetMaxEbsVolume tests ---

func TestGetMaxEbsVolume(t *testing.T) {
	limits := InstanceLimits{MaximumGp2: 1536, MaximumGp3: 3072}

	tests := []struct {
		storageClass string
		expected     int
	}{
		{"gp2", 1536},
		{"gp3", 3072},
		{"io1", -1},
		{"", -1},
	}

	for _, tt := range tests {
		t.Run(tt.storageClass, func(t *testing.T) {
			result := limits.GetMaxEbsVolume(tt.storageClass)
			if result != tt.expected {
				t.Errorf("GetMaxEbsVolume(%q) = %d, want %d", tt.storageClass, result, tt.expected)
			}
		})
	}
}

// --- Real instance lookup tests ---

func TestInstanceLimitsMap_R6gLarge(t *testing.T) {
	limits, found := InstanceLimitsMap["r6g.large.search"]
	if !found {
		t.Fatal("expected r6g.large.search in InstanceLimitsMap")
	}
	if !limits.IsStorageClassSupported("gp3") {
		t.Error("r6g.large.search should support gp3")
	}
	if limits.GetMaxEbsVolume("gp3") <= 0 {
		t.Error("r6g.large.search should have positive gp3 max volume")
	}
}

func TestInstanceLimitsMap_R3Large_NoGP3(t *testing.T) {
	limits, found := InstanceLimitsMap["r3.large.search"]
	if !found {
		t.Fatal("expected r3.large.search in InstanceLimitsMap")
	}
	if limits.IsStorageClassSupported("gp3") {
		t.Error("r3.large.search should NOT support gp3 (old gen)")
	}
	if !limits.IsStorageClassSupported("gp2") {
		t.Error("r3.large.search should support gp2")
	}
}

// TestOI2CacheCalculation verifies that cache ≈ 80% of instance storage (with rounding)
// and max addressable = 5 * cache, per AWS documentation
func TestOI2CacheCalculation(t *testing.T) {
	for instanceType, limits := range OI2WarmInstanceLimitsMap {
		t.Run(instanceType, func(t *testing.T) {
			// Cache should be approximately 80% of instance storage (AWS rounds these values)
			expectedCache := float64(limits.InstanceStorageGB) * 0.8
			tolerance := 1.0 // Allow 1 GB tolerance for rounding
			diff := float64(limits.CacheSizeGB) - expectedCache
			if diff < -tolerance || diff > tolerance {
				t.Errorf("Cache size for %s: got %d, expected ~%v (80%% of %d)",
					instanceType, limits.CacheSizeGB, expectedCache, limits.InstanceStorageGB)
			}

			// Max addressable should be exactly 5x cache (per AWS docs)
			expectedMaxAddressable := limits.CacheSizeGB * 5
			if limits.MaxAddressableStorageGB != expectedMaxAddressable {
				t.Errorf("Max addressable for %s: got %d, expected %d (5x cache)",
					instanceType, limits.MaxAddressableStorageGB, expectedMaxAddressable)
			}
		})
	}
}
