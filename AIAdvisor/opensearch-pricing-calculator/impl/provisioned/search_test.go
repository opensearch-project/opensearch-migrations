// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// --- GetDefaultSearchRequest tests ---

func TestGetDefaultSearchRequest(t *testing.T) {
	d := GetDefaultSearchRequest()
	assert.NotNil(t, d)
	assert.Equal(t, 25, d.TargetShardSize)
	assert.Equal(t, 1, d.Azs)
	assert.Equal(t, 1, d.Replicas)
	assert.Equal(t, 25, d.FreeStorageRequired)
	assert.True(t, d.DedicatedManager)
	assert.Equal(t, 10, d.ExpansionRate)
	assert.Equal(t, float32(1.5), d.CPUsPerShard)
	assert.Equal(t, 0.0, d.Edp)
	assert.Equal(t, "US East (N. Virginia)", d.Region)
	assert.Equal(t, "gp3", d.StorageClass)
	assert.Equal(t, 0, d.WarmPercentage)
	assert.Equal(t, 0, d.ColdPercentage)
	assert.Nil(t, d.AutoSelectWarmInstance)
}

// --- validateTierPercentages tests ---

func TestSearchValidateTierPercentagesNegativeValues(t *testing.T) {
	r := &SearchEstimateRequest{WarmPercentage: -10, ColdPercentage: -5}
	r.validateTierPercentages()
	assert.Equal(t, 0, r.WarmPercentage)
	assert.Equal(t, 0, r.ColdPercentage)
}

func TestSearchValidateTierPercentagesOverHundred(t *testing.T) {
	r := &SearchEstimateRequest{WarmPercentage: 150, ColdPercentage: 200}
	r.validateTierPercentages()
	assert.LessOrEqual(t, r.WarmPercentage, 100)
	assert.LessOrEqual(t, r.ColdPercentage, 100)
	assert.LessOrEqual(t, r.WarmPercentage+r.ColdPercentage, 100)
}

func TestSearchValidateTierPercentagesSumExceeds100(t *testing.T) {
	r := &SearchEstimateRequest{WarmPercentage: 60, ColdPercentage: 60}
	r.validateTierPercentages()
	assert.LessOrEqual(t, r.WarmPercentage+r.ColdPercentage, 100, "sum should not exceed 100")
}

func TestSearchValidateTierPercentagesValidValues(t *testing.T) {
	r := &SearchEstimateRequest{WarmPercentage: 30, ColdPercentage: 20}
	r.validateTierPercentages()
	assert.Equal(t, 30, r.WarmPercentage)
	assert.Equal(t, 20, r.ColdPercentage)
}

func TestSearchValidateTierPercentagesInvalidWarmInstanceType(t *testing.T) {
	r := &SearchEstimateRequest{WarmInstanceType: "invalid.instance.search"}
	r.validateTierPercentages()
	assert.Equal(t, "", r.WarmInstanceType, "invalid instance type should be reset")
}

func TestSearchValidateTierPercentagesValidWarmInstanceType(t *testing.T) {
	validTypes := []string{
		"ultrawarm1.medium.search", "ultrawarm1.large.search",
		"oi2.xlarge.search", "oi2.2xlarge.search", "oi2.4xlarge.search",
		"oi2.8xlarge.search", "oi2.16xlarge.search",
	}
	for _, wt := range validTypes {
		r := &SearchEstimateRequest{WarmInstanceType: wt}
		r.validateTierPercentages()
		assert.Equal(t, wt, r.WarmInstanceType, "valid type %q should be kept", wt)
	}
}

// --- isAutoSelectWarmInstance tests ---

func TestSearchIsAutoSelectWarmInstanceNil(t *testing.T) {
	r := &SearchEstimateRequest{}
	assert.True(t, r.isAutoSelectWarmInstance(), "nil should default to true")
}

func TestSearchIsAutoSelectWarmInstanceTrue(t *testing.T) {
	val := true
	r := &SearchEstimateRequest{AutoSelectWarmInstance: &val}
	assert.True(t, r.isAutoSelectWarmInstance())
}

func TestSearchIsAutoSelectWarmInstanceFalse(t *testing.T) {
	val := false
	r := &SearchEstimateRequest{AutoSelectWarmInstance: &val}
	assert.False(t, r.isAutoSelectWarmInstance())
}

// --- getStorageForTier tests ---

func TestSearchGetStorageForTierAllHot(t *testing.T) {
	r := &SearchEstimateRequest{WarmPercentage: 0, ColdPercentage: 0}
	hot, warm, cold := r.getStorageForTier(1000)
	assert.Equal(t, 1000.0, hot)
	assert.Equal(t, 0.0, warm)
	assert.Equal(t, 0.0, cold)
}

func TestSearchGetStorageForTierMixed(t *testing.T) {
	r := &SearchEstimateRequest{WarmPercentage: 30, ColdPercentage: 20}
	hot, warm, cold := r.getStorageForTier(1000)
	assert.InDelta(t, 500.0, hot, 0.01)
	assert.InDelta(t, 300.0, warm, 0.01)
	assert.InDelta(t, 200.0, cold, 0.01)
}

func TestSearchGetStorageForTierAllWarm(t *testing.T) {
	r := &SearchEstimateRequest{WarmPercentage: 100, ColdPercentage: 0}
	hot, warm, cold := r.getStorageForTier(1000)
	assert.Equal(t, 0.0, hot)
	assert.Equal(t, 1000.0, warm)
	assert.Equal(t, 0.0, cold)
}

func TestSearchGetStorageForTierZeroTotal(t *testing.T) {
	r := &SearchEstimateRequest{WarmPercentage: 50, ColdPercentage: 30}
	hot, warm, cold := r.getStorageForTier(0)
	assert.Equal(t, 0.0, hot)
	assert.Equal(t, 0.0, warm)
	assert.Equal(t, 0.0, cold)
}

// --- calculateWarmStorage tests ---

func TestSearchCalculateWarmStorageZero(t *testing.T) {
	r := &SearchEstimateRequest{ExpansionRate: 10}
	assert.Equal(t, 0.0, r.calculateWarmStorage(0))
}

func TestSearchCalculateWarmStorageWithExpansion(t *testing.T) {
	r := &SearchEstimateRequest{ExpansionRate: 10}
	result := r.calculateWarmStorage(100)
	// 100 * (1 + 10/100) = 100 * 1.1 = 110
	assert.InDelta(t, 110.0, result, 0.01)
}

func TestSearchCalculateWarmStorageZeroExpansion(t *testing.T) {
	r := &SearchEstimateRequest{ExpansionRate: 0}
	result := r.calculateWarmStorage(100)
	assert.InDelta(t, 100.0, result, 0.01)
}

// --- calculateColdStorage tests ---

func TestSearchCalculateColdStorageZero(t *testing.T) {
	r := &SearchEstimateRequest{}
	assert.Equal(t, 0.0, r.calculateColdStorage(0))
}

func TestSearchCalculateColdStorageNonZero(t *testing.T) {
	r := &SearchEstimateRequest{}
	result := r.calculateColdStorage(500)
	assert.Equal(t, 500.0, result)
}

// --- IsInstanceTypesAllowed tests ---

func TestSearchIsInstanceTypesAllowedEmptyList(t *testing.T) {
	r := &SearchEstimateRequest{InstanceTypes: []string{}}
	assert.True(t, r.IsInstanceTypesAllowed("r6g.xlarge.search"), "empty list should allow all")
}

func TestSearchIsInstanceTypesAllowedMatch(t *testing.T) {
	r := &SearchEstimateRequest{InstanceTypes: []string{"r6g"}}
	assert.True(t, r.IsInstanceTypesAllowed("r6g.xlarge.search"))
	assert.True(t, r.IsInstanceTypesAllowed("r6g.2xlarge.search"))
}

func TestSearchIsInstanceTypesAllowedNoMatch(t *testing.T) {
	r := &SearchEstimateRequest{InstanceTypes: []string{"r6g"}}
	assert.False(t, r.IsInstanceTypesAllowed("r7g.xlarge.search"))
	assert.False(t, r.IsInstanceTypesAllowed("m5.xlarge.search"))
}

func TestSearchIsInstanceTypesAllowedMultipleTypes(t *testing.T) {
	r := &SearchEstimateRequest{InstanceTypes: []string{"r6g", "r7g"}}
	assert.True(t, r.IsInstanceTypesAllowed("r6g.xlarge.search"))
	assert.True(t, r.IsInstanceTypesAllowed("r7g.2xlarge.search"))
	assert.False(t, r.IsInstanceTypesAllowed("m5.xlarge.search"))
}

// --- EstimateRequest.Validate tests ---

func TestEstimateRequestValidateAllSet(t *testing.T) {
	er := &EstimateRequest{
		TimeSeries: &TimeSeriesEstimateRequest{},
		Search:     &SearchEstimateRequest{},
		Vector:     &VectorEstimateRequest{},
	}
	err := er.Validate()
	assert.Error(t, err, "should error when all three are set")
}

func TestEstimateRequestValidateSearchOnly(t *testing.T) {
	er := &EstimateRequest{Search: &SearchEstimateRequest{}}
	err := er.Validate()
	assert.NoError(t, err)
}

func TestEstimateRequestValidateTimeSeriesOnly(t *testing.T) {
	er := &EstimateRequest{TimeSeries: &TimeSeriesEstimateRequest{}}
	err := er.Validate()
	assert.NoError(t, err)
}

func TestEstimateRequestValidateVectorOnly(t *testing.T) {
	er := &EstimateRequest{Vector: &VectorEstimateRequest{}}
	err := er.Validate()
	assert.NoError(t, err)
}

func TestEstimateRequestValidateNoneSet(t *testing.T) {
	er := &EstimateRequest{}
	err := er.Validate()
	assert.NoError(t, err)
}
