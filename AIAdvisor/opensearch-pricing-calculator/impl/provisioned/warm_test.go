package provisioned

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestAllWarmInstanceTypes_ContainsUltraWarmAndOI2(t *testing.T) {
	assert.Contains(t, AllWarmInstanceTypes, "ultrawarm1.medium.search")
	assert.Contains(t, AllWarmInstanceTypes, "ultrawarm1.large.search")
	assert.Contains(t, AllWarmInstanceTypes, "oi2.large.search")
	assert.Contains(t, AllWarmInstanceTypes, "oi2.xlarge.search")
	assert.Contains(t, AllWarmInstanceTypes, "oi2.2xlarge.search")
	assert.Contains(t, AllWarmInstanceTypes, "oi2.4xlarge.search")
	assert.Contains(t, AllWarmInstanceTypes, "oi2.8xlarge.search")
	assert.Equal(t, 7, len(AllWarmInstanceTypes))
}
