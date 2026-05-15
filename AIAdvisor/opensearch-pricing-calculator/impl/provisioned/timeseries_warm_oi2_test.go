package provisioned

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestTimeSeriesWarmAutoSelect_OI2NotPairedWithNonOpenSearchOptimized(t *testing.T) {
	req := TimeSeriesEstimateRequest{
		IngestionSize:       100,
		HotRetentionPeriod:  7,
		WarmRetentionPeriod: 30,
		Azs:                 3,
		Replicas:            1,
		CPUsPerShard:        1.25,
		Region:              "US East (N. Virginia)",
		PricingType:         "OnDemand",
		StorageClass:        "gp3",
		DedicatedManager:    true,
		InstanceTypes:       []string{"r6g"},
	}

	response, _ := req.Calculate()

	for _, cc := range response.ClusterConfigs {
		if cc.WarmNodes != nil {
			assert.False(t, isOI2WarmInstance(cc.WarmNodes.Type),
				"OI2 warm should not pair with Memory optimized hot node %s", cc.HotNodes.Type)
		}
	}
}

func TestTimeSeriesWarmAutoSelect_MultipleWarmCandidatesCompared(t *testing.T) {
	// Use large warm storage so multiple warm types are cost-competitive in the top 10
	req := TimeSeriesEstimateRequest{
		IngestionSize:       500,
		HotRetentionPeriod:  7,
		WarmRetentionPeriod: 90,
		Azs:                 3,
		Replicas:            1,
		CPUsPerShard:        1.25,
		Region:              "US East (N. Virginia)",
		PricingType:         "OnDemand",
		StorageClass:        "gp3",
		DedicatedManager:    true,
	}

	response, _ := req.Calculate()

	warmTypes := make(map[string]bool)
	for _, cc := range response.ClusterConfigs {
		if cc.WarmNodes != nil {
			warmTypes[cc.WarmNodes.Type] = true
		}
	}

	// Should see at least 2 different warm types (medium + large at minimum)
	assert.GreaterOrEqual(t, len(warmTypes), 2,
		"Auto-select should evaluate multiple warm instance types, got: %v", warmTypes)
	t.Logf("Warm instance types seen: %v", warmTypes)
}
