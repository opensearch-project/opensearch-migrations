// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cache

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// setupSecretTestRegions registers test Secret region mappings.
func setupSecretTestRegions() {
	regions.RegisterTestSecretRegions(map[string]regions.RegionInfo{
		"US ISO EAST (Ohio)": {
			ID:          "us-isob-east-1",
			DisplayName: "US ISO EAST (Ohio)",
			Continent:   "Secret regions",
			Currency:    "USD",
		},
		"US ISO WEST (Oregon)": {
			ID:          "us-isob-west-1",
			DisplayName: "US ISO WEST (Oregon)",
			Continent:   "Secret regions",
			Currency:    "USD",
		},
	})
}

func clearSecretTestRegions() {
	regions.RegisterTestSecretRegions(nil)
}

func TestIsSecretRegion(t *testing.T) {
	setupSecretTestRegions()
	defer clearSecretTestRegions()

	assert.True(t, IsSecretRegion("US ISO EAST (Ohio)"))
	assert.True(t, IsSecretRegion("US ISO WEST (Oregon)"))
	assert.False(t, IsSecretRegion("US East (N. Virginia)"))
	assert.False(t, IsSecretRegion(""))
}

func TestIsTopSecretRegion(t *testing.T) {
	regions.RegisterTestTopSecretRegions(map[string]regions.RegionInfo{
		"US ISO East": {
			ID:          "us-iso-east-1",
			DisplayName: "US ISO East",
			Continent:   "Top Secret regions",
			Currency:    "USD",
		},
	})
	defer regions.RegisterTestTopSecretRegions(nil)

	assert.True(t, IsTopSecretRegion("US ISO East"))
	assert.False(t, IsTopSecretRegion("US ISO EAST (Ohio)"))
	assert.False(t, IsTopSecretRegion(""))
}

func TestIsIsolatedRegion(t *testing.T) {
	setupSecretTestRegions()
	defer clearSecretTestRegions()
	regions.RegisterTestTopSecretRegions(map[string]regions.RegionInfo{
		"US ISO East": {
			ID:          "us-iso-east-1",
			DisplayName: "US ISO East",
			Continent:   "Top Secret regions",
			Currency:    "USD",
		},
	})
	defer regions.RegisterTestTopSecretRegions(nil)

	assert.True(t, IsIsolatedRegion("US ISO EAST (Ohio)"))
	assert.True(t, IsIsolatedRegion("US ISO East"))
	assert.False(t, IsIsolatedRegion("US East (N. Virginia)"))
}

// TestProcessTopSecretPricing_HotInstances tests Top Secret partition processing
// using the same format and offer term codes as Secret.
func TestProcessTopSecretPricing_HotInstances(t *testing.T) {
	data := map[string]interface{}{
		"regions": map[string]interface{}{
			"US ISO East": map[string]interface{}{
				"hash1": map[string]interface{}{
					"Instance Type": "m6g.xlarge.search",
					"vCPU":          "4",
					"Memory (GiB)":  "16",
					"Storage":       "EBS Only",
					"price":         "0.5970000000",
					"rateCode":      "SKU1.WCNHAN5AMX.6YS6EN2CT7",
				},
			},
			"US ISO WEST": map[string]interface{}{
				"hash2": map[string]interface{}{
					"Instance Type": "c6g.large.search",
					"vCPU":          "2",
					"Memory (GiB)":  "4",
					"Storage":       "EBS Only",
					"price":         "0.2830000000",
					"rateCode":      "SKU2.WCNHAN5AMX.6YS6EN2CT7",
				},
			},
		},
	}
	instanceServer := serveSecretJSON(t, data)
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processIsolatedPricingFromURLs("Top Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	east, ok := pp.Regions["US ISO East"]
	require.True(t, ok)
	iu, ok := east.HotInstances["m6g.xlarge.search"]
	require.True(t, ok)
	assert.Equal(t, 0.597, iu.Price["OnDemand"].Price)

	west, ok := pp.Regions["US ISO WEST"]
	require.True(t, ok)
	_, ok = west.HotInstances["c6g.large.search"]
	assert.True(t, ok)
}

// buildSecretInstanceJSON builds a test es-ultrawarm.json response with the Secret region
// format: hash keys, rateCode as SKU.OFFER_TERM.RATE_ID, Instance Type field.
func buildSecretInstanceJSON() map[string]interface{} {
	return map[string]interface{}{
		"regions": map[string]interface{}{
			"US ISO EAST (Ohio)": map[string]interface{}{
				// r6g.xlarge OnDemand
				"hash1": map[string]interface{}{
					"Instance Type":      "r6g.xlarge.search",
					"vCPU":               "4",
					"Memory (GiB)":       "32",
					"Storage":            "EBS Only",
					"price":              "0.7470000000",
					"rateCode":           "SKU1.WCNHAN5AMX.6YS6EN2CT7",
					"RegionlessRateCode": "hash1",
				},
				// r6g.xlarge 1yr Partial Upfront - hourly
				"hash2": map[string]interface{}{
					"Instance Type":      "r6g.xlarge.search",
					"vCPU":               "4",
					"Memory (GiB)":       "32",
					"Storage":            "EBS Only",
					"price":              "0.2500000000",
					"rateCode":           "SKU1.DGHNG4RYJT.6YS6EN2CT7",
					"RegionlessRateCode": "hash2",
				},
				// r6g.xlarge 1yr Partial Upfront - upfront fee
				"hash3": map[string]interface{}{
					"Instance Type":      "r6g.xlarge.search",
					"vCPU":               "4",
					"Memory (GiB)":       "32",
					"Storage":            "EBS Only",
					"price":              "2190.0000000000",
					"rateCode":           "SKU1.DGHNG4RYJT.2TG2D8R56U",
					"RegionlessRateCode": "hash3",
				},
				// r6g.xlarge 3yr All Upfront - upfront fee
				"hash4": map[string]interface{}{
					"Instance Type":      "r6g.xlarge.search",
					"vCPU":               "4",
					"Memory (GiB)":       "32",
					"Storage":            "EBS Only",
					"price":              "9422.9560000000",
					"rateCode":           "SKU1.UP3KH6A5GQ.2TG2D8R56U",
					"RegionlessRateCode": "hash4",
				},
				// r6g.xlarge 3yr All Upfront - hourly (0 for all upfront)
				"hash5": map[string]interface{}{
					"Instance Type":      "r6g.xlarge.search",
					"vCPU":               "4",
					"Memory (GiB)":       "32",
					"Storage":            "EBS Only",
					"price":              "0.0000000000",
					"rateCode":           "SKU1.UP3KH6A5GQ.6YS6EN2CT7",
					"RegionlessRateCode": "hash5",
				},
				// m6g.large OnDemand
				"hash6": map[string]interface{}{
					"Instance Type":      "m6g.large.search",
					"vCPU":               "2",
					"Memory (GiB)":       "8",
					"Storage":            "EBS Only",
					"price":              "0.1670000000",
					"rateCode":           "SKU2.WCNHAN5AMX.6YS6EN2CT7",
					"RegionlessRateCode": "hash6",
				},
				// ultrawarm1.medium OnDemand
				"hash7": map[string]interface{}{
					"Instance Type":      "ultrawarm1.medium.search",
					"vCPU":               "2",
					"Memory (GiB)":       "16",
					"price":              "0.5030000000",
					"rateCode":           "SKU3.WCNHAN5AMX.6YS6EN2CT7",
					"RegionlessRateCode": "hash7",
				},
				// i4i.xlarge with internal storage
				"hash8": map[string]interface{}{
					"Instance Type":      "i4i.xlarge.search",
					"vCPU":               "4",
					"Memory (GiB)":       "32",
					"Storage":            "1 x 937 Nitro SSD",
					"price":              "0.9090000000",
					"rateCode":           "SKU4.WCNHAN5AMX.6YS6EN2CT7",
					"RegionlessRateCode": "hash8",
				},
			},
			"US ISO WEST (Oregon)": map[string]interface{}{
				"hashW1": map[string]interface{}{
					"Instance Type":      "m6g.large.search",
					"vCPU":               "2",
					"Memory (GiB)":       "8",
					"Storage":            "EBS Only",
					"price":              "0.1750000000",
					"rateCode":           "SKU5.WCNHAN5AMX.6YS6EN2CT7",
					"RegionlessRateCode": "hashW1",
				},
			},
		},
	}
}

func buildSecretStorageJSON() map[string]interface{} {
	return map[string]interface{}{
		"regions": map[string]interface{}{
			"US ISO EAST (Ohio)": map[string]interface{}{
				"GP2": map[string]interface{}{
					"price":    "0.1600000000",
					"rateCode": "SECRET-GP2",
				},
				"GP3": map[string]interface{}{
					"price":    "0.1446000000",
					"rateCode": "SECRET-GP3",
				},
				"Magnetic": map[string]interface{}{
					"price":    "0.1400000000",
					"rateCode": "SECRET-MAG",
				},
				"Managed Storage per GigaBytes": map[string]interface{}{
					"price":    "0.0510000000",
					"rateCode": "SECRET-MS",
				},
			},
		},
	}
}

func serveSecretJSON(t *testing.T, data map[string]interface{}) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(data)
	}))
}

func TestProcessSecretPricing_HotInstances(t *testing.T) {
	instanceServer := serveSecretJSON(t, buildSecretInstanceJSON())
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processIsolatedPricingFromURLs("Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	// US ISO EAST should have hot instances
	east, ok := pp.Regions["US ISO EAST (Ohio)"]
	require.True(t, ok, "US ISO EAST (Ohio) should exist")

	iu, ok := east.HotInstances["r6g.xlarge.search"]
	require.True(t, ok, "r6g.xlarge.search should exist")
	assert.Equal(t, 4, iu.CPU)
	assert.Equal(t, 32.0, iu.Memory)
	assert.Equal(t, "Memory optimized", iu.Family)
	assert.Equal(t, 0.747, iu.Price["OnDemand"].Price)

	iu2, ok := east.HotInstances["m6g.large.search"]
	require.True(t, ok, "m6g.large.search should exist")
	assert.Equal(t, 2, iu2.CPU)
	assert.Equal(t, 0.167, iu2.Price["OnDemand"].Price)

	// US ISO WEST should have m6g.large
	west, ok := pp.Regions["US ISO WEST (Oregon)"]
	require.True(t, ok, "US ISO WEST (Oregon) should exist")
	iu3, ok := west.HotInstances["m6g.large.search"]
	require.True(t, ok, "m6g.large.search should exist in West")
	assert.Equal(t, 0.175, iu3.Price["OnDemand"].Price)
}

func TestProcessSecretPricing_ReservedInstances(t *testing.T) {
	instanceServer := serveSecretJSON(t, buildSecretInstanceJSON())
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processIsolatedPricingFromURLs("Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	iu := pp.Regions["US ISO EAST (Ohio)"].HotInstances["r6g.xlarge.search"]

	// OnDemand
	assert.Equal(t, 0.747, iu.Price["OnDemand"].Price)

	// 1yr Partial Upfront - hourly cost
	assert.Equal(t, 0.25, iu.Price["PURI1HC"].Price)
	assert.Equal(t, "SKU1.DGHNG4RYJT.6YS6EN2CT7", iu.Price["PURI1HC"].RateCode)

	// 1yr Partial Upfront - upfront fee
	assert.Equal(t, 2190.0, iu.Price["PURI1PC"].Price)
	assert.Equal(t, "SKU1.DGHNG4RYJT.2TG2D8R56U", iu.Price["PURI1PC"].RateCode)

	// 3yr All Upfront - upfront fee
	assert.Equal(t, 9422.956, iu.Price["AURI3PC"].Price)

	// 3yr All Upfront - hourly (should be 0)
	assert.Equal(t, 0.0, iu.Price["AURI3HC"].Price)
}

func TestProcessSecretPricing_WarmInstances(t *testing.T) {
	instanceServer := serveSecretJSON(t, buildSecretInstanceJSON())
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processIsolatedPricingFromURLs("Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	east := pp.Regions["US ISO EAST (Ohio)"]
	uw, ok := east.WarmInstances["ultrawarm1.medium.search"]
	require.True(t, ok, "ultrawarm1.medium.search should exist")
	assert.Equal(t, "UltraWarm", uw.Family)
	assert.Equal(t, 2, uw.CPU)
	assert.Equal(t, 16.0, uw.Memory)
	assert.Equal(t, 0.503, uw.Price["OnDemand"].Price)

	// UltraWarm should NOT be in hot instances
	_, inHot := east.HotInstances["ultrawarm1.medium.search"]
	assert.False(t, inHot, "ultrawarm should not appear in hot instances")
}

func TestProcessSecretPricing_InternalStorage(t *testing.T) {
	instanceServer := serveSecretJSON(t, buildSecretInstanceJSON())
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processIsolatedPricingFromURLs("Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	iu := pp.Regions["US ISO EAST (Ohio)"].HotInstances["i4i.xlarge.search"]
	assert.Equal(t, 937, iu.Storage.Internal)
}

func TestProcessSecretPricing_Storage(t *testing.T) {
	instanceServer := serveSecretJSON(t, buildSecretInstanceJSON())
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processIsolatedPricingFromURLs("Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	storage := pp.Regions["US ISO EAST (Ohio)"].Storage
	assert.Equal(t, 0.16, storage.Gp2.Price)
	assert.Equal(t, "SECRET-GP2", storage.Gp2.RateCode)
	assert.Equal(t, 0.1446, storage.Gp3.Price)
	assert.Equal(t, 0.14, storage.Magnetic.Price)
	assert.Equal(t, 0.051, storage.ManagedStorage.Price)
}

func TestProcessSecretPricing_DoesNotOverwriteExistingRegions(t *testing.T) {
	instanceServer := serveSecretJSON(t, buildSecretInstanceJSON())
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{
		Regions: map[string]price.ProvisionedRegion{
			"US East (N. Virginia)": {
				HotInstances: map[string]price.InstanceUnit{
					"r6g.xlarge.search": {InstanceType: "r6g.xlarge.search", CPU: 4},
				},
			},
		},
	}

	err := pp.processIsolatedPricingFromURLs("Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	_, ok := pp.Regions["US East (N. Virginia)"]
	assert.True(t, ok, "existing regions should be preserved")
	_, ok = pp.Regions["US ISO EAST (Ohio)"]
	assert.True(t, ok)
}

func TestProcessSecretPricing_HTTPError(t *testing.T) {
	errorServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer errorServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	// Instance data error should be fatal
	err := pp.processIsolatedPricingFromURLs("Secret",errorServer.URL, storageServer.URL)
	assert.Error(t, err)
}

func TestProcessSecretPricing_StorageErrorNonFatal(t *testing.T) {
	instanceServer := serveSecretJSON(t, buildSecretInstanceJSON())
	defer instanceServer.Close()
	errorServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer errorServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	// Storage error should be non-fatal
	err := pp.processIsolatedPricingFromURLs("Secret",instanceServer.URL, errorServer.URL)
	assert.NoError(t, err)

	// Hot instances should still be processed
	_, ok := pp.Regions["US ISO EAST (Ohio)"]
	assert.True(t, ok)
}

func TestProcessSecretPricing_UnknownOfferTermSkipped(t *testing.T) {
	// Entry with unknown offer term code should be silently skipped
	data := map[string]interface{}{
		"regions": map[string]interface{}{
			"US ISO EAST (Ohio)": map[string]interface{}{
				"hash1": map[string]interface{}{
					"Instance Type": "r6g.xlarge.search",
					"vCPU":          "4",
					"Memory (GiB)":  "32",
					"Storage":       "EBS Only",
					"price":         "0.7470000000",
					"rateCode":      "SKU1.WCNHAN5AMX.6YS6EN2CT7", // OnDemand
				},
				"hash2": map[string]interface{}{
					"Instance Type": "r6g.xlarge.search",
					"vCPU":          "4",
					"Memory (GiB)":  "32",
					"Storage":       "EBS Only",
					"price":         "99.99",
					"rateCode":      "SKU1.UNKNOWNTERM.6YS6EN2CT7", // Unknown term
				},
			},
		},
	}

	instanceServer := serveSecretJSON(t, data)
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processIsolatedPricingFromURLs("Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	iu := pp.Regions["US ISO EAST (Ohio)"].HotInstances["r6g.xlarge.search"]
	assert.Equal(t, 0.747, iu.Price["OnDemand"].Price)
	// Unknown term should not have been stored
	assert.Len(t, iu.Price, 1, "should only have OnDemand, unknown term should be skipped")
}

func TestProcessSecretPricing_JVMMemoryCap(t *testing.T) {
	data := map[string]interface{}{
		"regions": map[string]interface{}{
			"US ISO EAST (Ohio)": map[string]interface{}{
				"hash1": map[string]interface{}{
					"Instance Type": "r6g.8xlarge.search",
					"vCPU":          "32",
					"Memory (GiB)":  "256",
					"Storage":       "EBS Only",
					"price":         "2.4330000000",
					"rateCode":      "SKU1.WCNHAN5AMX.6YS6EN2CT7",
				},
			},
		},
	}

	instanceServer := serveSecretJSON(t, data)
	defer instanceServer.Close()
	storageServer := serveSecretJSON(t, buildSecretStorageJSON())
	defer storageServer.Close()

	setupSecretTestRegions()
	defer clearSecretTestRegions()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processIsolatedPricingFromURLs("Secret", instanceServer.URL, storageServer.URL)
	require.NoError(t, err)

	iu := pp.Regions["US ISO EAST (Ohio)"].HotInstances["r6g.8xlarge.search"]
	assert.Equal(t, 256.0, iu.Memory)
	assert.Equal(t, 30.5, iu.JVMMemory) // 256/2 = 128, capped at 30.5
}
