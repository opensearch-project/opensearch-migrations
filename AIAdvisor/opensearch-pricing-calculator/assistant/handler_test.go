package assistant

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestBuildProvisionedArgs_IncludesMaxConfigurations(t *testing.T) {
	pq := &ParsedQuery{
		WorkloadType: "search",
		Size:         500,
		Region:       "US East (N. Virginia)",
	}

	args := buildProvisionedArgs(pq)

	mc, ok := args["maxConfigurations"]
	assert.True(t, ok, "maxConfigurations should be present in args")
	assert.Equal(t, 3, mc)
}

func TestBuildProvisionedArgs_VectorIncludesMaxConfigurations(t *testing.T) {
	pq := &ParsedQuery{
		WorkloadType: "vector",
		Size:         100,
		Region:       "US East (N. Virginia)",
		VectorCount:  10000000,
		Dimensions:   768,
		VectorEngine: "hnsw",
	}

	args := buildProvisionedArgs(pq)

	mc, ok := args["maxConfigurations"]
	assert.True(t, ok, "maxConfigurations should be present in args")
	assert.Equal(t, 3, mc)
}

func TestBuildProvisionedArgs_TimeSeriesIncludesMaxConfigurations(t *testing.T) {
	pq := &ParsedQuery{
		WorkloadType: "timeseries",
		Size:         100,
		Region:       "US East (N. Virginia)",
		HotPeriod:    7,
		WarmPeriod:   30,
	}

	args := buildProvisionedArgs(pq)

	mc, ok := args["maxConfigurations"]
	assert.True(t, ok, "maxConfigurations should be present in args")
	assert.Equal(t, 3, mc)
}
