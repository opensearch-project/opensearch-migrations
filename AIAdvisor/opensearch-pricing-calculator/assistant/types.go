// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import "time"

// EstimateRequest represents the incoming request from frontend
type EstimateRequest struct {
	Query string `json:"query"`
}

// EstimateResponse represents the response sent to frontend
type EstimateResponse struct {
	Success              bool                   `json:"success"`
	WorkloadType         string                 `json:"workloadType,omitempty"`
	DeploymentPreference string                 `json:"deploymentPreference,omitempty"`
	Managed              map[string]interface{} `json:"managed,omitempty"`
	Serverless           map[string]interface{} `json:"serverless,omitempty"`
	Metadata             *Metadata              `json:"metadata,omitempty"`
	Error                string                 `json:"error,omitempty"`
	Suggestion           string                 `json:"suggestion,omitempty"`
	ParsedQuery          *ParsedQuery           `json:"parsedQuery,omitempty"`
	NeedsMoreInfo        bool                   `json:"needsMoreInfo,omitempty"`
	FollowUpQuestion     string                 `json:"followUpQuestion,omitempty"`
	AssistantMessage     string                 `json:"assistantMessage,omitempty"`
	ServerlessNote       string                 `json:"serverlessNote,omitempty"`
}

// Metadata contains processing information
type Metadata struct {
	ParsedWithLLM    bool    `json:"parsedWithLLM"`
	Confidence       float64 `json:"confidence"`
	ProcessingTimeMs int64   `json:"processingTimeMs"`
	CachedResponse   bool    `json:"cachedResponse"`
}

// ParsedQuery represents extracted parameters from NLP
type ParsedQuery struct {
	WorkloadType           string   `json:"workloadType"`
	Size                   int      `json:"size"`
	Region                 string   `json:"region"`
	VectorCount            int      `json:"vectorCount"`
	Dimensions             int      `json:"dimensions"`
	VectorEngine           string   `json:"vectorEngine"`
	OnDisk                 bool     `json:"onDisk"`
	CompressionLevel       int      `json:"compressionLevel"`
	DerivedSource          bool     `json:"derivedSource"`      // Enable derived source compression
	ZstdCompression        bool     `json:"zstdCompression"`    // Enable ZSTD index codec compression
	MultiAzWithStandby     bool     `json:"multiAzWithStandby"` // Enable Multi-AZ with Standby for 99.99% availability
	HotPeriod              int      `json:"hotPeriod"`
	WarmPeriod             int      `json:"warmPeriod"`
	ColdPeriod             int      `json:"coldPeriod"`             // Cold retention period for time-series
	WarmPercentage         int      `json:"warmPercentage"`         // Percentage of data in warm tier (vector/search)
	ColdPercentage         int      `json:"coldPercentage"`         // Percentage of data in cold tier (vector/search)
	WarmInstanceType       string   `json:"warmInstanceType"`       // Override warm instance type (ultrawarm1 or oi2)
	AutoSelectWarmInstance *bool    `json:"autoSelectWarmInstance"` // Auto-select warm instance type (default: true)
	Intent                 string   `json:"intent"`
	DeploymentPreference   string   `json:"deploymentPreference"`
	Confidence             float64  `json:"confidence"`
	RawQuery               string   `json:"rawQuery"`
	MissingParameters      []string `json:"missingParameters,omitempty"`
}

// RequiredParameter defines a required parameter for a workload type
type RequiredParameter struct {
	Name        string
	Description string
	Examples    []string
}

// WorkloadRequirements defines required parameters for each workload type
type WorkloadRequirements struct {
	WorkloadType string
	Parameters   []RequiredParameter
}

// GetWorkloadRequirements returns the required parameters for each workload type
func GetWorkloadRequirements() map[string]WorkloadRequirements {
	return map[string]WorkloadRequirements{
		"vector": {
			WorkloadType: "vector",
			Parameters: []RequiredParameter{
				{
					Name:        "size",
					Description: "Total data size to store",
					Examples:    []string{"100 GB", "5 TB", "1.5 TB"},
				},
				{
					Name:        "vectorCount",
					Description: "Number of vectors to store",
					Examples:    []string{"10 million vectors", "500M vectors", "1B vectors"},
				},
				{
					Name:        "dimensions",
					Description: "Dimensionality of vectors",
					Examples:    []string{"768 dimensions", "1536 dims", "384d"},
				},
			},
		},
		"search": {
			WorkloadType: "search",
			Parameters: []RequiredParameter{
				{
					Name:        "size",
					Description: "Total data size to store",
					Examples:    []string{"100 GB", "5 TB", "1.5 TB"},
				},
			},
		},
		"timeseries": {
			WorkloadType: "timeseries",
			Parameters: []RequiredParameter{
				{
					Name:        "size",
					Description: "Volume per period (daily, monthly, or total storage)",
					Examples:    []string{"100 GB per day", "50 GB daily", "10 TB total"},
				},
				{
					Name:        "hotPeriod",
					Description: "Retention period in hot tier",
					Examples:    []string{"7 days", "30d", "14 days in hot"},
				},
			},
		},
	}
}

// EnhancedLLMResponse represents the complete response from enhanced LLM
// Contains ready-to-use MCP request payloads
type EnhancedLLMResponse struct {
	WorkloadType         string                 `json:"workloadType"`
	DeploymentPreference string                 `json:"deploymentPreference"`
	EnvironmentIntent    string                 `json:"environmentIntent"` // production-performant, production-balanced, production-cost-optimized, dev
	ManagedRequest       map[string]interface{} `json:"managedRequest"`    // Complete MCP request for provisioned
	ServerlessRequest    map[string]interface{} `json:"serverlessRequest"` // Complete MCP request for serverless
	Confidence           float64                `json:"confidence"`
	RawQuery             string                 `json:"rawQuery"`
	Clarification        string                 `json:"-"` // Non-estimation response (clarification/explanation), not serialized
}

// ConversationMessage represents a single message in a conversation
type ConversationMessage struct {
	Role      string    `json:"role"`      // "user" or "assistant"
	Content   string    `json:"content"`   // Message content
	Timestamp time.Time `json:"timestamp"` // When message was sent
}

// ConversationSession represents a conversation session with history
type ConversationSession struct {
	ID           string                `json:"id"`           // Unique session ID
	Messages     []ConversationMessage `json:"messages"`     // Conversation history
	LastEnhanced *EnhancedLLMResponse  `json:"lastEnhanced"` // Last LLM-enhanced response
	LastQuery    *ParsedQuery          `json:"lastQuery"`    // Last parsed query
	CreatedAt    time.Time             `json:"createdAt"`    // Session creation time
	UpdatedAt    time.Time             `json:"updatedAt"`    // Last update time
	ExpiresAt    time.Time             `json:"expiresAt"`    // When session expires
}
