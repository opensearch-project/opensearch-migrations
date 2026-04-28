// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cache

import (
	"testing"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/stretchr/testify/assert"
)

func TestGetPriceKey1(t *testing.T) {
	tests := []struct {
		name        string
		priceTerm   string
		priceOption string
		expected    string
	}{
		{
			name:        "Partial Upfront 1 year",
			priceTerm:   "1 year",
			priceOption: "Partial Upfront",
			expected:    "PURI1",
		},
		{
			name:        "Partial Upfront 3 years",
			priceTerm:   "3 years",
			priceOption: "Partial Upfront",
			expected:    "PURI3",
		},
		{
			name:        "All Upfront 1 year",
			priceTerm:   "1 year",
			priceOption: "All Upfront",
			expected:    "AURI1",
		},
		{
			name:        "All Upfront 3 years",
			priceTerm:   "3 years",
			priceOption: "All Upfront",
			expected:    "AURI3",
		},
		{
			name:        "No Upfront 1 year",
			priceTerm:   "1 year",
			priceOption: "No Upfront",
			expected:    "NURI1",
		},
		{
			name:        "No Upfront 3 years",
			priceTerm:   "3 years",
			priceOption: "No Upfront",
			expected:    "NURI3",
		},
		{
			name:        "unknown option returns empty",
			priceTerm:   "1 year",
			priceOption: "Unknown",
			expected:    "",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			result := getPriceKey1(tc.priceTerm, tc.priceOption)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestGetPriceKeySuffix1(t *testing.T) {
	tests := []struct {
		name      string
		base      string
		priceTerm string
		expected  string
	}{
		{"1 year", "PURI", "1 year", "PURI1"},
		{"3 years", "AURI", "3 years", "AURI3"},
		{"other term defaults to 3", "NURI", "5 years", "NURI3"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			result := getPriceKeySuffix1(tc.base, tc.priceTerm)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestGetInstanceFamily(t *testing.T) {
	tests := []struct {
		name     string
		instance string
		expected string
	}{
		{"r6g memory optimized", "r6g.2xlarge.search", "Memory optimized"},
		{"r7g memory optimized", "r7g.xlarge.search", "Memory optimized"},
		{"m5 general purpose", "m5.xlarge.search", "General purpose"},
		{"m6g general purpose", "m6g.large.search", "General purpose"},
		{"t3 general purpose", "t3.medium.search", "General purpose"},
		{"c5 compute optimized", "c5.xlarge.search", "Compute optimized"},
		{"c6g compute optimized", "c6g.2xlarge.search", "Compute optimized"},
		{"i3 storage optimized", "i3.xlarge.search", "Storage optimized"},
		{"im4gn storage optimized", "im4gn.xlarge.search", "Storage optimized"},
		{"or1 opensearch optimized", "or1.2xlarge.search", "OR1"},
		{"unknown family", "unknown.xlarge.search", ""},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			result := getInstanceFamily(tc.instance)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestGetWarmInstanceType(t *testing.T) {
	tests := []struct {
		name     string
		input    map[string]interface{}
		expected string
	}{
		{
			name: "valid instance type",
			input: map[string]interface{}{
				"Instance Type": "ultrawarm1.medium.search",
				"vCPU":          "2",
			},
			expected: "ultrawarm1.medium.search",
		},
		{
			name: "no instance type key",
			input: map[string]interface{}{
				"vCPU":   "2",
				"Memory": "16",
			},
			expected: "",
		},
		{
			name:     "empty map",
			input:    map[string]interface{}{},
			expected: "",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			result := getWarmInstanceType(tc.input)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestUpdateWarmInstance(t *testing.T) {
	instances := make(map[string]price.InstanceUnit)

	itemValue := map[string]interface{}{
		"Instance Type": "ultrawarm1.medium.search",
		"vCPU":          "2",
		"Memory (GiB)":  "16",
		"price":         "0.24",
		"rateCode":      "UW-RC1",
	}

	updateWarmInstance(instances, itemValue)

	assert.Len(t, instances, 1)

	iu, ok := instances["ultrawarm1.medium.search"]
	assert.True(t, ok)
	assert.Equal(t, "ultrawarm1.medium.search", iu.InstanceType)
	assert.Equal(t, "UltraWarm", iu.Family)
	assert.Equal(t, 2, iu.CPU)
	assert.Equal(t, 16.0, iu.Memory)
	assert.Equal(t, 8.0, iu.JVMMemory) // 16/2 = 8, < 30.5
	assert.Equal(t, 0.24, iu.Price["OnDemand"].Price)
	assert.Equal(t, "UW-RC1", iu.Price["OnDemand"].RateCode)
}

func TestUpdateWarmInstance_JVMMemoryCap(t *testing.T) {
	instances := make(map[string]price.InstanceUnit)

	itemValue := map[string]interface{}{
		"Instance Type": "ultrawarm1.2xlarge.search",
		"vCPU":          "8",
		"Memory (GiB)":  "128",
		"price":         "1.50",
		"rateCode":      "UW-RC2",
	}

	updateWarmInstance(instances, itemValue)

	iu := instances["ultrawarm1.2xlarge.search"]
	assert.Equal(t, 128.0, iu.Memory)
	assert.Equal(t, 30.5, iu.JVMMemory) // 128/2 = 64, capped at 30.5
}

func TestUpdateWarmInstance_EmptyInstanceType(t *testing.T) {
	instances := make(map[string]price.InstanceUnit)

	itemValue := map[string]interface{}{
		"vCPU":  "2",
		"price": "0.24",
	}

	updateWarmInstance(instances, itemValue)
	assert.Empty(t, instances)
}

func TestUpdateHotInstance1_OnDemand(t *testing.T) {
	instances := make(map[string]price.InstanceUnit)

	itemValue := map[string]interface{}{
		"Instance Type": "r6g.2xlarge.search",
		"vCPU":          "8",
		"Memory (GiB)":  "64",
		"price":         "0.878",
		"rateCode":      "HOT-RC1",
		"Storage":       "EBS Only",
	}

	updateHotInstance1(instances, itemValue, "OnDemand")

	assert.Len(t, instances, 1)
	iu, ok := instances["r6g.2xlarge.search"]
	assert.True(t, ok)
	assert.Equal(t, "r6g.2xlarge.search", iu.InstanceType)
	assert.Equal(t, 8, iu.CPU)
	assert.Equal(t, 64.0, iu.Memory)
	assert.Equal(t, 30.5, iu.JVMMemory) // 64/2 = 32, capped at 30.5
	assert.Equal(t, 0.878, iu.Price["OnDemand"].Price)
	assert.Equal(t, "HOT-RC1", iu.Price["OnDemand"].RateCode)
}

func TestUpdateHotInstance1_SkipsUltraWarm(t *testing.T) {
	instances := make(map[string]price.InstanceUnit)

	itemValue := map[string]interface{}{
		"Instance Type": "ultrawarm1.medium.search",
		"vCPU":          "2",
		"price":         "0.24",
	}

	updateHotInstance1(instances, itemValue, "OnDemand")
	assert.Empty(t, instances)
}

func TestUpdateHotInstance1_SkipsEmptyInstanceType(t *testing.T) {
	instances := make(map[string]price.InstanceUnit)

	itemValue := map[string]interface{}{
		"Instance Type": "",
		"vCPU":          "2",
		"price":         "0.24",
	}

	updateHotInstance1(instances, itemValue, "OnDemand")
	assert.Empty(t, instances)
}

func TestUpdateHotInstance1_ReservedInstance(t *testing.T) {
	instances := make(map[string]price.InstanceUnit)

	// First add the on-demand entry
	onDemandValue := map[string]interface{}{
		"Instance Type": "r6g.xlarge.search",
		"vCPU":          "4",
		"Memory (GiB)":  "32",
		"price":         "0.439",
		"rateCode":      "OD-RC",
		"Storage":       "EBS Only",
	}
	updateHotInstance1(instances, onDemandValue, "OnDemand")

	// Now add RI pricing
	riValue := map[string]interface{}{
		"Instance Type":      "r6g.xlarge.search",
		"vCPU":               "4",
		"Memory (GiB)":       "32",
		"price":              "0.20",
		"rateCode":           "RI-HC-RC",
		"riupfront:RateCode": "RI-PC-RC",
		"riupfront:PricePerUnit": "1500.00",
	}
	updateHotInstance1(instances, riValue, "PURI1")

	iu := instances["r6g.xlarge.search"]
	// On-demand price should still be there
	assert.Equal(t, 0.439, iu.Price["OnDemand"].Price)
	// RI hourly cost (HC)
	assert.Equal(t, 0.20, iu.Price["PURI1HC"].Price)
	assert.Equal(t, "RI-HC-RC", iu.Price["PURI1HC"].RateCode)
	// RI prepaid cost (PC)
	assert.Equal(t, 1500.0, iu.Price["PURI1PC"].Price)
	assert.Equal(t, "RI-PC-RC", iu.Price["PURI1PC"].RateCode)
}

func TestCreateNewOnDemandHotInstanceUnit_EBSOnly(t *testing.T) {
	value := map[string]interface{}{
		"Instance Type": "r6g.xlarge.search",
		"Storage":       "EBS Only",
	}

	iu := createNewOnDemandHotInstanceUnit(value)
	assert.Equal(t, "r6g.xlarge.search", iu.InstanceType)
	assert.Equal(t, "Memory optimized", iu.Family)
	assert.NotNil(t, iu.Price)
}

func TestCreateNewOnDemandHotInstanceUnit_InternalStorage(t *testing.T) {
	value := map[string]interface{}{
		"Instance Type": "i3.xlarge.search",
		"Storage":       "1 x 950 GB",
	}

	iu := createNewOnDemandHotInstanceUnit(value)
	assert.Equal(t, "i3.xlarge.search", iu.InstanceType)
	assert.Equal(t, 950, iu.Storage.Internal) // 1 * 950
}

func TestCreateNewOnDemandHotInstanceUnit_SkipsUltraWarm(t *testing.T) {
	value := map[string]interface{}{
		"Instance Type": "ultrawarm1.medium.search",
	}

	iu := createNewOnDemandHotInstanceUnit(value)
	assert.Empty(t, iu.InstanceType)
}

func TestProvisionedPrice_GetAllRegions(t *testing.T) {
	pp := &ProvisionedPrice{
		Regions: map[string]price.ProvisionedRegion{
			"US East (N. Virginia)": {},
			"EU (Ireland)":         {},
		},
	}

	regions := pp.GetAllRegions()
	assert.Len(t, regions, 2)
	assert.Contains(t, regions, "US East (N. Virginia)")
	assert.Contains(t, regions, "EU (Ireland)")
}

func TestProvisionedPrice_GetAllRegions_Empty(t *testing.T) {
	pp := &ProvisionedPrice{
		Regions: map[string]price.ProvisionedRegion{},
	}

	regions := pp.GetAllRegions()
	assert.Empty(t, regions)
}

func TestToMap(t *testing.T) {
	// Valid map
	m := map[string]interface{}{"key": "value"}
	result, ok := toMap(m)
	assert.True(t, ok)
	assert.Equal(t, m, result)

	// Not a map — should not panic
	result, ok = toMap("a string")
	assert.False(t, ok)
	assert.Nil(t, result)

	// Nil value
	result, ok = toMap(nil)
	assert.False(t, ok)
	assert.Nil(t, result)

	// Integer
	result, ok = toMap(42)
	assert.False(t, ok)
	assert.Nil(t, result)
}

func TestToString(t *testing.T) {
	assert.Equal(t, "hello", toString("hello"))
	assert.Equal(t, "", toString(nil))
	assert.Equal(t, "", toString(42))
	assert.Equal(t, "", toString(3.14))
	assert.Equal(t, "", toString(map[string]interface{}{}))
}

func TestUpdateWarmInstance_NonStringValues(t *testing.T) {
	// Verify that non-string values in the pricing data don't cause panics
	instances := make(map[string]price.InstanceUnit)

	itemValue := map[string]interface{}{
		"Instance Type": "ultrawarm1.medium.search",
		"vCPU":          2,      // int instead of string
		"Memory (GiB)":  16.0,   // float instead of string
		"price":         0.24,   // float instead of string
		"rateCode":      123,    // int instead of string
	}

	// Should not panic — toString returns "" for non-strings
	updateWarmInstance(instances, itemValue)
	assert.Len(t, instances, 1)
	iu := instances["ultrawarm1.medium.search"]
	assert.Equal(t, "UltraWarm", iu.Family)
}

func TestUpdateHotInstance1_NonStringValues(t *testing.T) {
	instances := make(map[string]price.InstanceUnit)

	// Instance Type as string so it gets created, but other values as wrong types
	itemValue := map[string]interface{}{
		"Instance Type": "r6g.xlarge.search",
		"vCPU":          4,      // int instead of string
		"price":         0.439,  // float instead of string
		"Storage":       "EBS Only",
	}

	// Should not panic
	updateHotInstance1(instances, itemValue, "OnDemand")
	assert.Len(t, instances, 1)
}

func TestCreateNewOnDemandHotInstanceUnit_MissingInstanceType(t *testing.T) {
	// nil value for Instance Type should not panic
	value := map[string]interface{}{
		"Storage": "EBS Only",
	}

	iu := createNewOnDemandHotInstanceUnit(value)
	assert.Empty(t, iu.InstanceType) // toString(nil) returns ""
}
