// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cache

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestIsChinaRegion(t *testing.T) {
	assert.True(t, IsChinaRegion("China (Beijing)"))
	assert.True(t, IsChinaRegion("China (Ningxia)"))
	assert.False(t, IsChinaRegion("US East (N. Virginia)"))
	assert.False(t, IsChinaRegion(""))
}

func TestChinaPriceKey(t *testing.T) {
	tests := []struct {
		name     string
		attrs    chinaTermAttributes
		expected string
	}{
		{"Partial Upfront 1yr", chinaTermAttributes{"1yr", "Partial Upfront"}, "PURI1"},
		{"Partial Upfront 3yr", chinaTermAttributes{"3yr", "Partial Upfront"}, "PURI3"},
		{"All Upfront 1yr", chinaTermAttributes{"1yr", "All Upfront"}, "AURI1"},
		{"All Upfront 3yr", chinaTermAttributes{"3yr", "All Upfront"}, "AURI3"},
		{"No Upfront 1yr", chinaTermAttributes{"1yr", "No Upfront"}, "NURI1"},
		{"No Upfront 3yr", chinaTermAttributes{"3yr", "No Upfront"}, "NURI3"},
		{"Unknown option", chinaTermAttributes{"1yr", "Unknown"}, ""},
		{"Unknown term", chinaTermAttributes{"5yr", "All Upfront"}, ""},
		{"Empty", chinaTermAttributes{}, ""},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(t, tc.expected, chinaPriceKey(tc.attrs))
		})
	}
}

func TestParseCNY(t *testing.T) {
	assert.Equal(t, 34.311, parseCNY(map[string]string{"CNY": "34.3110000000"}))
	assert.Equal(t, 0.0, parseCNY(map[string]string{"USD": "1.00"}))
	assert.Equal(t, 0.0, parseCNY(map[string]string{}))
	assert.Equal(t, 0.0, parseCNY(map[string]string{"CNY": "not-a-number"}))
}

// buildTestChinaPricingJSON constructs a minimal but representative China Bulk Pricing response.
func buildTestChinaPricingJSON() chinaBulkPricing {
	return chinaBulkPricing{
		Products: map[string]chinaProduct{
			"SKU_HOT1": {
				SKU:           "SKU_HOT1",
				ProductFamily: "Amazon OpenSearch Service Instance",
				Attributes: chinaProductAttributes{
					Location:       "China (Beijing)",
					InstanceType:   "r6g.xlarge.search",
					VCPU:           "4",
					MemoryGiB:      "32",
					Storage:        "EBS Only",
					InstanceFamily: "Memory optimized",
					RegionCode:     "cn-north-1",
				},
			},
			"SKU_HOT2": {
				SKU:           "SKU_HOT2",
				ProductFamily: "Amazon OpenSearch Service Instance",
				Attributes: chinaProductAttributes{
					Location:       "China (Ningxia)",
					InstanceType:   "m6g.large.search",
					VCPU:           "2",
					MemoryGiB:      "8",
					Storage:        "EBS Only",
					InstanceFamily: "General purpose",
					RegionCode:     "cn-northwest-1",
				},
			},
			"SKU_WARM1": {
				SKU:           "SKU_WARM1",
				ProductFamily: "Amazon OpenSearch Service Instance",
				Attributes: chinaProductAttributes{
					Location:     "China (Beijing)",
					InstanceType: "ultrawarm1.medium.search",
					VCPU:         "2",
					MemoryGiB:    "16",
					RegionCode:   "cn-north-1",
				},
			},
			"SKU_STORAGE_GP2": {
				SKU:           "SKU_STORAGE_GP2",
				ProductFamily: "Amazon OpenSearch Service Volume",
				Attributes: chinaProductAttributes{
					Location:     "China (Beijing)",
					StorageMedia: "GP2",
					RegionCode:   "cn-north-1",
				},
			},
			"SKU_STORAGE_GP3": {
				SKU:           "SKU_STORAGE_GP3",
				ProductFamily: "Amazon OpenSearch Service Volume",
				Attributes: chinaProductAttributes{
					Location:     "China (Beijing)",
					StorageMedia: "GP3",
					RegionCode:   "cn-north-1",
				},
			},
			"SKU_STORAGE_MAGNETIC": {
				SKU:           "SKU_STORAGE_MAGNETIC",
				ProductFamily: "Amazon OpenSearch Service Volume",
				Attributes: chinaProductAttributes{
					Location:     "China (Beijing)",
					StorageMedia: "Magnetic",
					RegionCode:   "cn-north-1",
				},
			},
			"SKU_STORAGE_MANAGED": {
				SKU:           "SKU_STORAGE_MANAGED",
				ProductFamily: "Amazon OpenSearch Service Volume",
				Attributes: chinaProductAttributes{
					Location:     "China (Beijing)",
					StorageMedia: "Managed-Storage",
					RegionCode:   "cn-north-1",
				},
			},
		},
		Terms: chinaTerms{
			OnDemand: map[string]map[string]chinaOffer{
				"SKU_HOT1": {
					"SKU_HOT1.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_HOT1.OD.RC": {
								RateCode:     "SKU_HOT1.OD.RC",
								Unit:         "Hrs",
								PricePerUnit: map[string]string{"CNY": "6.4130000000"},
							},
						},
					},
				},
				"SKU_HOT2": {
					"SKU_HOT2.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_HOT2.OD.RC": {
								RateCode:     "SKU_HOT2.OD.RC",
								Unit:         "Hrs",
								PricePerUnit: map[string]string{"CNY": "1.2340000000"},
							},
						},
					},
				},
				"SKU_WARM1": {
					"SKU_WARM1.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_WARM1.OD.RC": {
								RateCode:     "SKU_WARM1.OD.RC",
								Unit:         "Hrs",
								PricePerUnit: map[string]string{"CNY": "1.7870000000"},
							},
						},
					},
				},
				"SKU_STORAGE_GP2": {
					"SKU_STORAGE_GP2.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_STORAGE_GP2.OD.RC": {
								RateCode:     "SKU_STORAGE_GP2.OD.RC",
								Unit:         "GB-Mo",
								PricePerUnit: map[string]string{"CNY": "1.0070000000"},
							},
						},
					},
				},
				"SKU_STORAGE_GP3": {
					"SKU_STORAGE_GP3.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_STORAGE_GP3.OD.RC": {
								RateCode:     "SKU_STORAGE_GP3.OD.RC",
								Unit:         "GB-Mo",
								PricePerUnit: map[string]string{"CNY": "0.9101000000"},
							},
						},
					},
				},
				"SKU_STORAGE_MAGNETIC": {
					"SKU_STORAGE_MAGNETIC.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_STORAGE_MAGNETIC.OD.RC": {
								RateCode:     "SKU_STORAGE_MAGNETIC.OD.RC",
								Unit:         "GB-Mo",
								PricePerUnit: map[string]string{"CNY": "0.5520000000"},
							},
						},
					},
				},
				"SKU_STORAGE_MANAGED": {
					"SKU_STORAGE_MANAGED.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_STORAGE_MANAGED.OD.RC": {
								RateCode:     "SKU_STORAGE_MANAGED.OD.RC",
								Unit:         "GigaBytes",
								PricePerUnit: map[string]string{"CNY": "0.2070000000"},
							},
						},
					},
				},
			},
			Reserved: map[string]map[string]chinaOffer{
				"SKU_HOT1": {
					// Partial Upfront 1yr
					"SKU_HOT1.PURI1": {
						TermAttributes: chinaTermAttributes{
							LeaseContractLength: "1yr",
							PurchaseOption:      "Partial Upfront",
						},
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_HOT1.PURI1.HC": {
								RateCode:     "SKU_HOT1.PURI1.HC",
								Description:  "r6g.xlarge.search reserved instance applied",
								Unit:         "Hrs",
								PricePerUnit: map[string]string{"CNY": "3.1000000000"},
							},
							"SKU_HOT1.PURI1.PC": {
								RateCode:     "SKU_HOT1.PURI1.PC",
								Description:  "Upfront Fee",
								Unit:         "Quantity",
								PricePerUnit: map[string]string{"CNY": "27156"},
							},
						},
					},
					// No Upfront 3yr
					"SKU_HOT1.NURI3": {
						TermAttributes: chinaTermAttributes{
							LeaseContractLength: "3yr",
							PurchaseOption:      "No Upfront",
						},
						PriceDimensions: map[string]chinaPriceDim{
							"SKU_HOT1.NURI3.HC": {
								RateCode:     "SKU_HOT1.NURI3.HC",
								Unit:         "Hrs",
								PricePerUnit: map[string]string{"CNY": "2.5000000000"},
							},
						},
					},
				},
			},
		},
	}
}

func serveTestChinaPricing(t *testing.T, data chinaBulkPricing) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(data)
	}))
}

func TestProcessChinaPricing_HotInstances(t *testing.T) {
	server := serveTestChinaPricing(t, buildTestChinaPricingJSON())
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	require.NoError(t, err)

	// Beijing should have the r6g.xlarge
	beijing, ok := pp.Regions["China (Beijing)"]
	require.True(t, ok, "China (Beijing) should exist")
	assert.Equal(t, "CNY", beijing.Currency)

	iu, ok := beijing.HotInstances["r6g.xlarge.search"]
	require.True(t, ok, "r6g.xlarge.search should exist in Beijing")
	assert.Equal(t, 4, iu.CPU)
	assert.Equal(t, 32.0, iu.Memory)
	assert.Equal(t, "Memory optimized", iu.Family)
	assert.Equal(t, 6.413, iu.Price["OnDemand"].Price)
	assert.Equal(t, "SKU_HOT1.OD.RC", iu.Price["OnDemand"].RateCode)

	// Ningxia should have m6g.large
	ningxia, ok := pp.Regions["China (Ningxia)"]
	require.True(t, ok, "China (Ningxia) should exist")
	assert.Equal(t, "CNY", ningxia.Currency)

	iu2, ok := ningxia.HotInstances["m6g.large.search"]
	require.True(t, ok, "m6g.large.search should exist in Ningxia")
	assert.Equal(t, 2, iu2.CPU)
	assert.Equal(t, 8.0, iu2.Memory)
	assert.Equal(t, 1.234, iu2.Price["OnDemand"].Price)
}

func TestProcessChinaPricing_WarmInstances(t *testing.T) {
	server := serveTestChinaPricing(t, buildTestChinaPricingJSON())
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	require.NoError(t, err)

	beijing := pp.Regions["China (Beijing)"]
	uw, ok := beijing.WarmInstances["ultrawarm1.medium.search"]
	require.True(t, ok, "ultrawarm1.medium.search should exist")
	assert.Equal(t, "UltraWarm", uw.Family)
	assert.Equal(t, 2, uw.CPU)
	assert.Equal(t, 16.0, uw.Memory)
	assert.Equal(t, 1.787, uw.Price["OnDemand"].Price)

	// UltraWarm should NOT be in hot instances
	_, inHot := beijing.HotInstances["ultrawarm1.medium.search"]
	assert.False(t, inHot, "ultrawarm should not appear in hot instances")
}

func TestProcessChinaPricing_Storage(t *testing.T) {
	server := serveTestChinaPricing(t, buildTestChinaPricingJSON())
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	require.NoError(t, err)

	storage := pp.Regions["China (Beijing)"].Storage
	assert.Equal(t, 1.007, storage.Gp2.Price)
	assert.Equal(t, 0.9101, storage.Gp3.Price)
	assert.Equal(t, 0.552, storage.Magnetic.Price)
	assert.Equal(t, 0.207, storage.ManagedStorage.Price)
}

func TestProcessChinaPricing_ReservedInstances(t *testing.T) {
	server := serveTestChinaPricing(t, buildTestChinaPricingJSON())
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	require.NoError(t, err)

	iu := pp.Regions["China (Beijing)"].HotInstances["r6g.xlarge.search"]

	// Partial Upfront 1yr hourly cost
	assert.Equal(t, 3.1, iu.Price["PURI1HC"].Price)
	assert.Equal(t, "SKU_HOT1.PURI1.HC", iu.Price["PURI1HC"].RateCode)

	// Partial Upfront 1yr upfront fee
	assert.Equal(t, 27156.0, iu.Price["PURI1PC"].Price)
	assert.Equal(t, "SKU_HOT1.PURI1.PC", iu.Price["PURI1PC"].RateCode)

	// No Upfront 3yr hourly cost
	assert.Equal(t, 2.5, iu.Price["NURI3HC"].Price)

	// No Upfront should have no PC entry
	_, hasPC := iu.Price["NURI3PC"]
	assert.False(t, hasPC, "No Upfront should not have a prepaid cost entry")
}

func TestProcessChinaPricing_DoesNotOverwriteExistingRegions(t *testing.T) {
	server := serveTestChinaPricing(t, buildTestChinaPricingJSON())
	defer server.Close()

	pp := &ProvisionedPrice{
		Regions: map[string]price.ProvisionedRegion{
			"US East (N. Virginia)": {
				HotInstances: map[string]price.InstanceUnit{
					"r6g.xlarge.search": {InstanceType: "r6g.xlarge.search", CPU: 4},
				},
			},
		},
	}

	err := pp.processChinaPricingFromURL(server.URL)
	require.NoError(t, err)

	// US region should still be there
	_, ok := pp.Regions["US East (N. Virginia)"]
	assert.True(t, ok, "existing regions should be preserved")

	// China regions should also be there
	_, ok = pp.Regions["China (Beijing)"]
	assert.True(t, ok)
	_, ok = pp.Regions["China (Ningxia)"]
	assert.True(t, ok)
}

func TestProcessChinaPricing_HTTPError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "status 500")
}

func TestProcessChinaPricing_InvalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("not json"))
	}))
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "failed to parse China pricing JSON")
}

func TestProcessChinaPricing_EmptyProducts(t *testing.T) {
	server := serveTestChinaPricing(t, chinaBulkPricing{
		Products: map[string]chinaProduct{},
		Terms:    chinaTerms{},
	})
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	require.NoError(t, err)
	assert.Empty(t, pp.Regions)
}

func TestProcessChinaPricing_JVMMemoryCap(t *testing.T) {
	data := chinaBulkPricing{
		Products: map[string]chinaProduct{
			"SKU1": {
				SKU:           "SKU1",
				ProductFamily: "Amazon OpenSearch Service Instance",
				Attributes: chinaProductAttributes{
					Location:     "China (Beijing)",
					InstanceType: "r6g.8xlarge.search",
					VCPU:         "32",
					MemoryGiB:    "256",
					Storage:      "EBS Only",
				},
			},
		},
		Terms: chinaTerms{
			OnDemand: map[string]map[string]chinaOffer{
				"SKU1": {
					"SKU1.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU1.OD.RC": {
								RateCode:     "RC1",
								Unit:         "Hrs",
								PricePerUnit: map[string]string{"CNY": "25.674"},
							},
						},
					},
				},
			},
		},
	}

	server := serveTestChinaPricing(t, data)
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	require.NoError(t, err)

	iu := pp.Regions["China (Beijing)"].HotInstances["r6g.8xlarge.search"]
	assert.Equal(t, 256.0, iu.Memory)
	assert.Equal(t, 30.5, iu.JVMMemory) // 256/2 = 128, capped at 30.5
}

func TestProcessChinaPricing_InternalStorage(t *testing.T) {
	data := chinaBulkPricing{
		Products: map[string]chinaProduct{
			"SKU1": {
				SKU:           "SKU1",
				ProductFamily: "Amazon OpenSearch Service Instance",
				Attributes: chinaProductAttributes{
					Location:     "China (Beijing)",
					InstanceType: "i3.xlarge.search",
					VCPU:         "4",
					MemoryGiB:    "30.5",
					Storage:      "1 x 950 GB",
				},
			},
		},
		Terms: chinaTerms{
			OnDemand: map[string]map[string]chinaOffer{
				"SKU1": {
					"SKU1.OD": {
						PriceDimensions: map[string]chinaPriceDim{
							"SKU1.OD.RC": {
								RateCode:     "RC1",
								Unit:         "Hrs",
								PricePerUnit: map[string]string{"CNY": "5.00"},
							},
						},
					},
				},
			},
		},
	}

	server := serveTestChinaPricing(t, data)
	defer server.Close()

	pp := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
	err := pp.processChinaPricingFromURL(server.URL)
	require.NoError(t, err)

	iu := pp.Regions["China (Beijing)"].HotInstances["i3.xlarge.search"]
	assert.Equal(t, 950, iu.Storage.Internal) // 1 * 950
}

func TestRegionBuilderSetStoragePrice_AllTypes(t *testing.T) {
	rb := &regionBuilder{
		hotInstances:  make(map[string]price.InstanceUnit),
		warmInstances: make(map[string]price.InstanceUnit),
	}

	rb.setStoragePrice("GP2", "rc1", 1.007)
	rb.setStoragePrice("GP3", "rc2", 0.910)
	rb.setStoragePrice("GP3-PIOPS-Storage", "rc3", 0.059)
	rb.setStoragePrice("GP3-ThroughPut-Storage", "rc4", 0.477)
	rb.setStoragePrice("PIOPS", "rc5", 0.605)
	rb.setStoragePrice("PIOPS-Storage", "rc6", 1.158)
	rb.setStoragePrice("Magnetic", "rc7", 0.552)
	rb.setStoragePrice("Managed-Storage", "rc8", 0.207)

	region := rb.build()
	assert.Equal(t, 1.007, region.Storage.Gp2.Price)
	assert.Equal(t, 0.910, region.Storage.Gp3.Price)
	assert.Equal(t, 0.059, region.Storage.Gp3ProvisionedIOPS.Price)
	assert.Equal(t, 0.477, region.Storage.Gp3Provisioned.Price)
	assert.Equal(t, 0.605, region.Storage.Gp2ProvisionedIOPS.Price)
	assert.Equal(t, 1.158, region.Storage.Gp2Provisioned.Price)
	assert.Equal(t, 0.552, region.Storage.Magnetic.Price)
	assert.Equal(t, 0.207, region.Storage.ManagedStorage.Price)
}
