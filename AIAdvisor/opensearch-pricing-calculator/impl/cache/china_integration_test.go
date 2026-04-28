// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

//go:build integration

package cache

import (
	"fmt"
	"sort"
	"testing"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestChinaPricingIntegration calls the real AWS China Pricing API.
// Run with: go test ./impl/cache/ -tags=integration -run TestChinaPricingIntegration -v -timeout 5m
func TestChinaPricingIntegration(t *testing.T) {
	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}

	err := pp.processChinaPricingFromURL(price.ChinaPricingUrl)
	require.NoError(t, err, "failed to download/process China pricing")

	// Should have exactly 2 regions
	assert.Len(t, pp.Regions, 2, "expected 2 China regions")
	assert.Contains(t, pp.Regions, "China (Beijing)")
	assert.Contains(t, pp.Regions, "China (Ningxia)")

	for regionName, region := range pp.Regions {
		t.Run(regionName, func(t *testing.T) {
			// Currency
			assert.Equal(t, "CNY", region.Currency)

			// --- Hot Instances ---
			assert.NotEmpty(t, region.HotInstances, "should have hot instances")
			fmt.Printf("\n=== %s: %d hot instances ===\n", regionName, len(region.HotInstances))

			hotNames := make([]string, 0, len(region.HotInstances))
			for name := range region.HotInstances {
				hotNames = append(hotNames, name)
			}
			sort.Strings(hotNames)
			for _, name := range hotNames {
				iu := region.HotInstances[name]
				od := iu.Price["OnDemand"]
				fmt.Printf("  %-30s  vCPU=%-3d  Mem=%-7.1f  OnDemand=CNY %.4f/hr", name, iu.CPU, iu.Memory, od.Price)

				// Count RI tiers available
				riCount := 0
				for _, key := range []string{"PURI1HC", "PURI3HC", "AURI1HC", "AURI3HC", "NURI1HC", "NURI3HC"} {
					if _, ok := iu.Price[key]; ok {
						riCount++
					}
				}
				if riCount > 0 {
					fmt.Printf("  RI tiers: %d/6", riCount)
				}
				fmt.Println()

				// Validate basics
				assert.NotEmpty(t, iu.InstanceType)
				assert.NotEmpty(t, iu.Family, "family should be set for %s", name)
				assert.Greater(t, iu.CPU, 0, "vCPU should be > 0 for %s", name)
				assert.Greater(t, iu.Memory, 0.0, "memory should be > 0 for %s", name)
				assert.Greater(t, od.Price, 0.0, "OnDemand price should be > 0 for %s", name)
				assert.NotEmpty(t, od.RateCode, "OnDemand rateCode should be set for %s", name)
			}

			// --- Warm Instances ---
			fmt.Printf("\n=== %s: %d warm instances ===\n", regionName, len(region.WarmInstances))
			for name, iu := range region.WarmInstances {
				od := iu.Price["OnDemand"]
				fmt.Printf("  %-30s  vCPU=%-3d  Mem=%-7.1f  OnDemand=CNY %.4f/hr\n", name, iu.CPU, iu.Memory, od.Price)
				assert.Equal(t, "UltraWarm", iu.Family)
				assert.Greater(t, od.Price, 0.0, "warm OnDemand should be > 0 for %s", name)
			}

			// --- Storage ---
			fmt.Printf("\n=== %s: Storage Pricing ===\n", regionName)
			s := region.Storage
			storageChecks := []struct {
				name string
				unit price.Unit
			}{
				{"GP2", s.Gp2},
				{"GP3", s.Gp3},
				{"GP3 Provisioned IOPS", s.Gp3ProvisionedIOPS},
				{"GP3 Provisioned Throughput", s.Gp3Provisioned},
				{"PIOPS (IO1 IOPS)", s.Gp2ProvisionedIOPS},
				{"PIOPS Storage (IO1)", s.Gp2Provisioned},
				{"Magnetic", s.Magnetic},
				{"Managed Storage", s.ManagedStorage},
			}
			for _, sc := range storageChecks {
				fmt.Printf("  %-30s  CNY %.6f  rateCode=%s\n", sc.name, sc.unit.Price, sc.unit.RateCode)
			}

			// GP2 and GP3 should always be present
			assert.Greater(t, s.Gp2.Price, 0.0, "GP2 price should be > 0")
			assert.Greater(t, s.Gp3.Price, 0.0, "GP3 price should be > 0")
			assert.Greater(t, s.Magnetic.Price, 0.0, "Magnetic price should be > 0")
			assert.Greater(t, s.ManagedStorage.Price, 0.0, "ManagedStorage price should be > 0")

			// --- Spot-check a known instance ---
			if regionName == "China (Beijing)" {
				iu, ok := region.HotInstances["r6g.xlarge.search"]
				if assert.True(t, ok, "r6g.xlarge.search should exist in Beijing") {
					assert.Equal(t, 4, iu.CPU)
					assert.Equal(t, 32.0, iu.Memory)
					assert.Equal(t, "Memory optimized", iu.Family)
					assert.Greater(t, iu.Price["OnDemand"].Price, 0.0)
				}
			}
		})
	}
}
