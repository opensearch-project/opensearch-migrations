package assistant

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestGenerateAssistantMessage_BothAvailable(t *testing.T) {
	f := NewResponseFormatter()

	managed := map[string]interface{}{
		"totalMonthlyCost": 1247.32,
		"topClusterConfigurations": []interface{}{
			map[string]interface{}{"costs": map[string]interface{}{"totalMonthlyCost": 1247.32}},
			map[string]interface{}{"costs": map[string]interface{}{"totalMonthlyCost": 1589.00}},
			map[string]interface{}{"costs": map[string]interface{}{"totalMonthlyCost": 1892.50}},
		},
	}
	serverless := map[string]interface{}{
		"price": map[string]interface{}{
			"month": map[string]interface{}{
				"total": 892.45,
			},
		},
	}
	pq := &ParsedQuery{
		WorkloadType: "search",
		Size:         500,
		Region:       "US East (N. Virginia)",
	}

	msg := f.generateAssistantMessage(pq, managed, serverless)

	assert.Contains(t, msg, "search")
	assert.Contains(t, msg, "US East (N. Virginia)")
	assert.Contains(t, msg, "$1,247.32")
	assert.Contains(t, msg, "$1,892.50")
	assert.Contains(t, msg, "$892.45")
	assert.Contains(t, msg, "serverless")
}

func TestGenerateAssistantMessage_ManagedOnly(t *testing.T) {
	f := NewResponseFormatter()

	managed := map[string]interface{}{
		"totalMonthlyCost": 1247.32,
		"topClusterConfigurations": []interface{}{
			map[string]interface{}{"costs": map[string]interface{}{"totalMonthlyCost": 1247.32}},
			map[string]interface{}{"costs": map[string]interface{}{"totalMonthlyCost": 1892.50}},
		},
	}
	pq := &ParsedQuery{
		WorkloadType: "vector",
		Size:         100,
		Region:       "US West (Oregon)",
		VectorCount:  10000000,
		Dimensions:   768,
		VectorEngine: "hnsw",
	}

	msg := f.generateAssistantMessage(pq, managed, nil)

	assert.Contains(t, msg, "vector")
	assert.Contains(t, msg, "US West (Oregon)")
	assert.Contains(t, msg, "$1,247.32")
	assert.Contains(t, msg, "$1,892.50")
	assert.NotContains(t, msg, "serverless")
}

func TestGenerateAssistantMessage_ServerlessOnly(t *testing.T) {
	f := NewResponseFormatter()

	serverless := map[string]interface{}{
		"price": map[string]interface{}{
			"month": map[string]interface{}{
				"total": 450.00,
			},
		},
	}
	pq := &ParsedQuery{
		WorkloadType: "timeseries",
		Size:         50,
		Region:       "EU (Frankfurt)",
	}

	msg := f.generateAssistantMessage(pq, nil, serverless)

	assert.Contains(t, msg, "time-series")
	assert.Contains(t, msg, "EU (Frankfurt)")
	assert.Contains(t, msg, "$450.00")
	assert.NotContains(t, msg, "managed")
}

func TestGenerateAssistantMessage_NeitherAvailable(t *testing.T) {
	f := NewResponseFormatter()

	pq := &ParsedQuery{
		WorkloadType: "search",
		Region:       "US East (N. Virginia)",
	}

	msg := f.generateAssistantMessage(pq, nil, nil)

	assert.Contains(t, msg, "unable to generate")
}

func TestFormat_PopulatesAssistantMessage(t *testing.T) {
	f := NewResponseFormatter()

	managed := map[string]interface{}{
		"clusterConfigs": []interface{}{
			map[string]interface{}{
				"totalCost": 1247.32,
				"hotNodes": map[string]interface{}{
					"type":   "or1.xlarge.search",
					"count":  float64(6),
					"family": "OR1",
				},
			},
		},
		"totalHotStorage": float64(500),
	}
	pq := &ParsedQuery{
		WorkloadType:         "search",
		Size:                 500,
		Region:               "US East (N. Virginia)",
		DeploymentPreference: "both",
	}
	metadata := &Metadata{Confidence: 0.95}

	response := f.Format(pq, managed, nil, metadata)

	assert.NotEmpty(t, response.AssistantMessage, "AssistantMessage should be populated")
	assert.Contains(t, response.AssistantMessage, "search")
	assert.Contains(t, response.AssistantMessage, "$1,247.32")
}

func TestFormat_ServerlessNote_WhenServerlessNil(t *testing.T) {
	f := NewResponseFormatter()

	managed := map[string]interface{}{
		"clusterConfigs": []interface{}{
			map[string]interface{}{
				"totalCost": 500.00,
				"hotNodes": map[string]interface{}{
					"type":   "r6g.xlarge.search",
					"count":  float64(3),
					"family": "Memory optimized",
				},
			},
		},
		"totalHotStorage": float64(100),
	}
	pq := &ParsedQuery{
		WorkloadType:         "search",
		Size:                 100,
		Region:               "US East (N. Virginia)",
		DeploymentPreference: "both",
	}
	metadata := &Metadata{Confidence: 0.95}

	response := f.Format(pq, managed, nil, metadata)

	assert.Equal(t, "Serverless is not available for this configuration.", response.ServerlessNote)
}

func TestFormat_NoServerlessNote_WhenBothAvailable(t *testing.T) {
	f := NewResponseFormatter()

	managed := map[string]interface{}{
		"clusterConfigs": []interface{}{
			map[string]interface{}{
				"totalCost": 500.00,
				"hotNodes": map[string]interface{}{
					"type":   "r6g.xlarge.search",
					"count":  float64(3),
					"family": "Memory optimized",
				},
			},
		},
		"totalHotStorage": float64(100),
	}
	serverless := map[string]interface{}{
		"region": "US East (N. Virginia)",
		"price": map[string]interface{}{
			"month": map[string]interface{}{
				"total": 300.00,
			},
		},
	}
	pq := &ParsedQuery{
		WorkloadType:         "search",
		Size:                 100,
		Region:               "US East (N. Virginia)",
		DeploymentPreference: "both",
	}
	metadata := &Metadata{Confidence: 0.95}

	response := f.Format(pq, managed, serverless, metadata)

	assert.Empty(t, response.ServerlessNote)
}
