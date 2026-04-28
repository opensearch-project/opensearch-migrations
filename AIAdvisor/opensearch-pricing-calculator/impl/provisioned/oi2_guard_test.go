package provisioned

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestOI2WarmGuard_AllWorkloads_NoOI2WithNonOpenSearchOptimized exhaustively verifies
// that OI2 warm nodes NEVER appear with non-OpenSearch Optimized hot nodes across all workload types.
// This scans EVERY config in the response — no filtering by instance family.
func TestOI2WarmGuard_AllWorkloads_NoOI2WithNonOpenSearchOptimized(t *testing.T) {
	t.Run("search_all_families", func(t *testing.T) {
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
		}

		response, err := req.Calculate()
		require.NoError(t, err)
		require.True(t, len(response.ClusterConfigs) > 0, "should have cluster configs")

		oi2WarmCount := 0
		for i, cc := range response.ClusterConfigs {
			if cc.WarmNodes != nil && isOI2WarmInstance(cc.WarmNodes.Type) {
				oi2WarmCount++
				assert.True(t, isOpenSearchOptimizedInstance(cc.HotNodes.Type),
					"Config %d: OI2 warm %s MUST NOT pair with non-OpenSearch-Optimized hot %s",
					i, cc.WarmNodes.Type, cc.HotNodes.Type)
			}
		}
		t.Logf("Checked %d configs, %d had OI2 warm nodes", len(response.ClusterConfigs), oi2WarmCount)
	})

	t.Run("search_r8g_specifically", func(t *testing.T) {
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
			InstanceTypes:    []string{"r8g.xlarge.search"},
		}

		response, err := req.Calculate()
		require.NoError(t, err)

		for _, cc := range response.ClusterConfigs {
			if cc.WarmNodes != nil {
				assert.False(t, isOI2WarmInstance(cc.WarmNodes.Type),
					"r8g.xlarge hot node MUST NOT have OI2 warm %s — only UltraWarm allowed",
					cc.WarmNodes.Type)
			}
		}
	})

	t.Run("vector_all_families", func(t *testing.T) {
		req := &VectorEstimateRequest{
			DataSize:                   100,
			VectorCount:                10000000,
			DimensionsCount:            768,
			VectorEngineType:           "hnsw",
			VectorMemoryCircuitBreaker: 75,
			WarmPercentage:             30,
			Azs:                        3,
			Replicas:                   1,
			CPUsPerShard:               1.5,
			Region:                     "US East (N. Virginia)",
			PricingType:                "OnDemand",
			StorageClass:               "gp3",
			DedicatedManager:           true,
		}

		response, err := req.Calculate()
		require.NoError(t, err)
		require.True(t, len(response.ClusterConfigs) > 0, "should have cluster configs")

		oi2WarmCount := 0
		for i, cc := range response.ClusterConfigs {
			if cc.WarmNodes != nil && isOI2WarmInstance(cc.WarmNodes.Type) {
				oi2WarmCount++
				assert.True(t, isOpenSearchOptimizedInstance(cc.HotNodes.Type),
					"Config %d: OI2 warm %s MUST NOT pair with non-OpenSearch-Optimized hot %s",
					i, cc.WarmNodes.Type, cc.HotNodes.Type)
			}
		}
		t.Logf("Checked %d configs, %d had OI2 warm nodes", len(response.ClusterConfigs), oi2WarmCount)
	})

	t.Run("timeseries_all_families", func(t *testing.T) {
		req := &TimeSeriesEstimateRequest{
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
		}

		response, err := req.Calculate()
		require.NoError(t, err)
		require.True(t, len(response.ClusterConfigs) > 0, "should have cluster configs")

		oi2WarmCount := 0
		for i, cc := range response.ClusterConfigs {
			if cc.WarmNodes != nil && isOI2WarmInstance(cc.WarmNodes.Type) {
				oi2WarmCount++
				assert.True(t, isOpenSearchOptimizedInstance(cc.HotNodes.Type),
					"Config %d: OI2 warm %s MUST NOT pair with non-OpenSearch-Optimized hot %s",
					i, cc.WarmNodes.Type, cc.HotNodes.Type)
			}
		}
		t.Logf("Checked %d configs, %d had OI2 warm nodes", len(response.ClusterConfigs), oi2WarmCount)
	})

	t.Run("timeseries_r8g_specifically", func(t *testing.T) {
		req := &TimeSeriesEstimateRequest{
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
			InstanceTypes:       []string{"r8g.xlarge.search"},
		}

		response, err := req.Calculate()
		require.NoError(t, err)

		for _, cc := range response.ClusterConfigs {
			if cc.WarmNodes != nil {
				assert.False(t, isOI2WarmInstance(cc.WarmNodes.Type),
					"r8g.xlarge hot node MUST NOT have OI2 warm %s — only UltraWarm allowed",
					cc.WarmNodes.Type)
			}
		}
	})
}
