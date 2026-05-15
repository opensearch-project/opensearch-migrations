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

// runIsolatedPricingTest is a shared helper for testing isolated partition pricing.
func runIsolatedPricingTest(t *testing.T, partitionLabel, instanceUrl, storageUrl string) {
	t.Helper()
	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}

	err := pp.processIsolatedPricingFromURLs(partitionLabel, instanceUrl, storageUrl)
	require.NoError(t, err, "failed to download/process %s region pricing", partitionLabel)

	assert.NotEmpty(t, pp.Regions, "should have at least one %s region", partitionLabel)

	for regionName, region := range pp.Regions {
		t.Run(regionName, func(t *testing.T) {
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
				fmt.Printf("  %-30s  vCPU=%-3d  Mem=%-7.1f  OnDemand=USD %.4f/hr", name, iu.CPU, iu.Memory, od.Price)

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

				assert.NotEmpty(t, iu.InstanceType)
				assert.NotEmpty(t, iu.Family, "family should be set for %s", name)
				assert.Greater(t, iu.CPU, 0, "vCPU should be > 0 for %s", name)
				assert.Greater(t, iu.Memory, 0.0, "memory should be > 0 for %s", name)
				assert.Greater(t, od.Price, 0.0, "OnDemand price should be > 0 for %s", name)
				assert.NotEmpty(t, od.RateCode, "OnDemand rateCode should be set for %s", name)
			}

			fmt.Printf("\n=== %s: %d warm instances ===\n", regionName, len(region.WarmInstances))
			for name, iu := range region.WarmInstances {
				od := iu.Price["OnDemand"]
				fmt.Printf("  %-30s  vCPU=%-3d  Mem=%-7.1f  OnDemand=USD %.4f/hr\n", name, iu.CPU, iu.Memory, od.Price)
				assert.Equal(t, "UltraWarm", iu.Family)
				assert.Greater(t, od.Price, 0.0, "warm OnDemand should be > 0 for %s", name)
			}

			fmt.Printf("\n=== %s: Storage Pricing ===\n", regionName)
			s := region.Storage
			for _, sc := range []struct {
				name string
				unit price.Unit
			}{
				{"GP2", s.Gp2}, {"GP3", s.Gp3},
				{"GP3 Provisioned IOPS", s.Gp3ProvisionedIOPS},
				{"GP3 Provisioned Throughput", s.Gp3Provisioned},
				{"PIOPS (IO1 IOPS)", s.Gp2ProvisionedIOPS},
				{"PIOPS Storage (IO1)", s.Gp2Provisioned},
				{"Magnetic", s.Magnetic}, {"Managed Storage", s.ManagedStorage},
			} {
				fmt.Printf("  %-30s  USD %.6f  rateCode=%s\n", sc.name, sc.unit.Price, sc.unit.RateCode)
			}

			assert.Greater(t, s.Gp2.Price, 0.0, "GP2 price should be > 0")
			assert.Greater(t, s.Gp3.Price, 0.0, "GP3 price should be > 0")
		})
	}
}

// TestSecretPricingIntegration calls the real AWS Secret Region pricing endpoints.
// Run with: go test ./impl/cache/ -tags=integration -run TestSecretPricingIntegration -v -timeout 5m
func TestSecretPricingIntegration(t *testing.T) {
	runIsolatedPricingTest(t, "Secret", price.SecretInstanceUrl, price.SecretStorageUrl)
}

// TestTopSecretPricingIntegration calls the real AWS Top Secret Region pricing endpoints.
// Run with: go test ./impl/cache/ -tags=integration -run TestTopSecretPricingIntegration -v -timeout 5m
func TestTopSecretPricingIntegration(t *testing.T) {
	runIsolatedPricingTest(t, "Top Secret", price.TopSecretInstanceUrl, price.TopSecretStorageUrl)
}

// TestSecretServerlessPricingIntegration attempts to fetch Secret Region serverless pricing.
// Run with: go test ./impl/cache/ -tags=integration -run TestSecretServerlessPricingIntegration -v -timeout 5m
func TestSecretServerlessPricingIntegration(t *testing.T) {
	sp := &ServerlessPrice{Regions: make(map[string]price.ServerlessRegion)}
	response := sp.invalidateFromURL(price.SecretServerlessPricingUrl)
	fmt.Printf("Secret serverless pricing response: %s\n", response.Message)
}

// TestTopSecretServerlessPricingIntegration attempts to fetch Top Secret Region serverless pricing.
// Run with: go test ./impl/cache/ -tags=integration -run TestTopSecretServerlessPricingIntegration -v -timeout 5m
func TestTopSecretServerlessPricingIntegration(t *testing.T) {
	sp := &ServerlessPrice{Regions: make(map[string]price.ServerlessRegion)}
	response := sp.invalidateFromURL(price.TopSecretServerlessPricingUrl)
	fmt.Printf("Top Secret serverless pricing response: %s\n", response.Message)
}
