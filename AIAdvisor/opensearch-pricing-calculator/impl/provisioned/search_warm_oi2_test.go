package provisioned

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestSearchWarmAutoSelect_OI2NotPairedWithNonOpenSearchOptimized(t *testing.T) {
	req := &SearchEstimateRequest{
		DataSize:         500,
		WarmPercentage:   30,
		Azs:              3,
		Replicas:         1,
		CPUsPerShard:     1.5,
		Region:           "US East (N. Virginia)",
		PricingType:      "OnDemand",
		StorageClass:     "gp3",
		DedicatedManager: true,
		InstanceTypes:    []string{"r6g.xlarge.search"}, // Memory optimized, non-OpenSearch Optimized
	}

	response, err := req.Calculate()
	assert.NoError(t, err)

	// Verify that any warm nodes returned are NOT OI2 types
	// (OI2 should only pair with OpenSearch Optimized hot nodes like or1/or2/oi2)
	for _, cc := range response.ClusterConfigs {
		if cc.WarmNodes != nil {
			assert.False(t, isOI2WarmInstance(cc.WarmNodes.Type),
				"OI2 warm should not pair with Memory optimized hot node %s", cc.HotNodes.Type)
		}
	}
}
