// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/bedrockruntime"
	"go.uber.org/zap"
)

// LLMEnhancer uses AWS Bedrock to generate complete MCP request payloads
type LLMEnhancer struct {
	client    *bedrockruntime.Client
	modelID   string
	awsRegion string
	logger    *zap.Logger
}

// NewLLMEnhancer creates a new LLM enhancer instance
func NewLLMEnhancer(ctx context.Context, logger *zap.Logger) (*LLMEnhancer, error) {
	awsRegion := os.Getenv("AWS_REGION")
	if awsRegion == "" {
		awsRegion = "us-west-2"
	}

	profile := os.Getenv("AWS_PROFILE")

	var cfg aws.Config
	var err error

	if profile != "" {
		// Use profile for local development
		cfg, err = config.LoadDefaultConfig(ctx,
			config.WithRegion(awsRegion),
			config.WithSharedConfigProfile(profile),
		)
	} else {
		// Use default credentials (IAM role in ECS)
		cfg, err = config.LoadDefaultConfig(ctx,
			config.WithRegion(awsRegion),
		)
	}

	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}

	client := bedrockruntime.NewFromConfig(cfg)

	modelID := os.Getenv("BEDROCK_MODEL_ID")
	if modelID == "" {
		modelID = "us.anthropic.claude-opus-4-5-20251101-v1:0"
	}

	return &LLMEnhancer{
		client:    client,
		modelID:   modelID,
		awsRegion: awsRegion,
		logger:    logger,
	}, nil
}

// EnhanceToMCP uses LLM to generate complete MCP request payloads from query
// This is the new enhanced version that returns ready-to-use MCP requests
func (e *LLMEnhancer) EnhanceToMCP(ctx context.Context, query string) (*EnhancedLLMResponse, error) {
	return e.EnhanceWithContext(ctx, query, nil)
}

// EnhanceWithContext uses LLM with conversation history to generate complete MCP request payloads
// This enables conversational interactions and follow-up questions
func (e *LLMEnhancer) EnhanceWithContext(ctx context.Context, query string, history []ConversationMessage) (*EnhancedLLMResponse, error) {
	// Feature flag: Use Bedrock Tool Calling API
	// Set USE_TOOL_CALLING=true to enable native tool use
	useToolCalling := os.Getenv("USE_TOOL_CALLING") == "true"

	// Log the decision
	if useToolCalling {
		if e.logger != nil {
			e.logger.Info("Using Bedrock Tool Calling API",
				zap.String("approach", "tool_use"),
				zap.String("USE_TOOL_CALLING", "true"),
				zap.Int("historyLength", len(history)))
		}
		return e.enhanceWithToolUse(ctx, query, history)
	}

	// Fallback to prompt-based approach
	if e.logger != nil {
		e.logger.Info("Using prompt-based approach",
			zap.String("approach", "prompt_based"),
			zap.String("USE_TOOL_CALLING", "false"),
			zap.Int("historyLength", len(history)))
	}
	prompt := e.buildComprehensivePromptWithContext(query, history)

	response, err := e.invokeModel(ctx, prompt)
	if err != nil {
		return nil, fmt.Errorf("failed to invoke Bedrock model: %w", err)
	}

	enhanced, err := e.parseEnhancedResponse(response, query)
	if err != nil {
		return nil, fmt.Errorf("failed to parse LLM response: %w", err)
	}

	return enhanced, nil
}

// Enhance maintains backward compatibility with existing code
// It converts EnhancedLLMResponse back to ParsedQuery format
func (e *LLMEnhancer) Enhance(ctx context.Context, query string, initialParse *ParsedQuery) (*ParsedQuery, error) {
	enhanced, err := e.EnhanceToMCP(ctx, query)
	if err != nil {
		// Fallback to initial parse on error
		return initialParse, err
	}

	// Convert enhanced response to ParsedQuery for backward compatibility
	parsed := &ParsedQuery{
		RawQuery:             query,
		WorkloadType:         enhanced.WorkloadType,
		DeploymentPreference: enhanced.DeploymentPreference,
		Confidence:           enhanced.Confidence,
		Intent:               enhanced.EnvironmentIntent,
	}

	// Extract basic parameters from managed request if available
	if enhanced.ManagedRequest != nil {
		e.extractParametersFromManagedRequest(enhanced.ManagedRequest, parsed)
	}

	return parsed, nil
}

func (e *LLMEnhancer) extractParametersFromManagedRequest(managedReq map[string]interface{}, parsed *ParsedQuery) {
	// Try to find the workload config (search, vector, or timeSeries)
	var workloadConfig map[string]interface{}

	for _, key := range []string{"search", "vector", "timeSeries"} {
		if config, ok := managedReq[key].(map[string]interface{}); ok {
			workloadConfig = config
			break
		}
	}

	if workloadConfig == nil {
		return
	}

	// Extract common fields
	if size, ok := workloadConfig["size"].(float64); ok {
		parsed.Size = int(size)
	}
	if region, ok := workloadConfig["region"].(string); ok {
		parsed.Region = region
	}

	// Extract vector-specific fields
	if parsed.WorkloadType == "vector" {
		if vc, ok := workloadConfig["vectorCount"].(float64); ok {
			parsed.VectorCount = int(vc)
		}
		if dims, ok := workloadConfig["dimensionsCount"].(float64); ok {
			parsed.Dimensions = int(dims)
		}
		if engine, ok := workloadConfig["vectorEngineType"].(string); ok {
			parsed.VectorEngine = engine
		}
	}

	// Extract timeseries-specific fields
	if parsed.WorkloadType == "timeseries" || parsed.WorkloadType == "timeSeries" {
		if hot, ok := workloadConfig["hotRetentionPeriod"].(float64); ok {
			parsed.HotPeriod = int(hot)
		}
		if warm, ok := workloadConfig["warmRetentionPeriod"].(float64); ok {
			parsed.WarmPeriod = int(warm)
		}
	}
}

func (e *LLMEnhancer) buildComprehensivePrompt(query string) string {
	// Read the comprehensive prompt template
	promptTemplate := `You are an expert OpenSearch architect specializing in cost estimation and infrastructure configuration.

USER QUERY: "%s"

TASK:
Analyze the query and generate complete MCP request payloads for both Managed (Provisioned) and Serverless OpenSearch deployments.

ANALYSIS STEPS:

1. WORKLOAD IDENTIFICATION
   Determine the primary workload type:
   - "vector": Vector similarity search (requires vectorCount, dimensions, vectorEngineType)
   - "search": Full-text search workloads
   - "timeSeries": Time-series/log analytics data

2. ENVIRONMENT INTENT CLASSIFICATION
   Classify the deployment environment based on query language:

   - "production-performant": Keywords like "high performance", "low latency", "mission critical", "production scale", "high availability", "99.99%%"
     → Use: 3 AZs, 2 replicas, 2.0 CPUsPerShard, 32GB minimumJVM, dedicatedManager=true, preferInternalStorage=true, multiAzWithStandby=true
     → Vector: Use HNSW engines (hnsw, hnswfp16), onDisk=false or compressionLevel=2-4 if needed
     → Storage: NVME or gp3, freeStorageRequired=25%%

   - "production-balanced": Keywords like "production", "reliable", "balanced" (default for production)
     → Use: 3 AZs, 1 replica, 1.5 CPUsPerShard, 8GB minimumJVM, dedicatedManager=true, multiAzWithStandby=false
     → Vector: Use hnswfp16 or ivffp16, compressionLevel=8-16 if onDisk
     → Storage: gp3, freeStorageRequired=25%%

   - "production-cost-optimized": Keywords like "cost effective", "budget", "optimized cost" in production context
     → Use: 3 AZs, 1 replica, 1.0 CPUsPerShard, 8GB minimumJVM, dedicatedManager=true, multiAzWithStandby=false
     → Vector: Use compressed engines (hnswint8, hnswpq, ivfpq), compressionLevel=16-32 if onDisk
     → Storage: gp3 or standard, freeStorageRequired=25%%

   - "dev": Keywords like "dev", "test", "development", "sandbox", "POC", "prototype", "small"
     → Use: 1 AZ, 0 replicas, 1.0 CPUsPerShard, 0-4GB minimumJVM, dedicatedManager=false, multiAzWithStandby=false
     → Vector: Use ivfpq or compressed engines, compressionLevel=32 if onDisk
     → Storage: gp3, freeStorageRequired=15-20%%

3. DEPLOYMENT PREFERENCE
   - "managed": User explicitly mentions "managed", "provisioned", "dedicated cluster"
   - "serverless": User explicitly mentions "serverless", "on-demand"
   - "both": Default - generate estimates for both deployment types

4. PARAMETER EXTRACTION
   Extract from query:
   - Size: NON-VECTOR document data volume in GB (convert TB/PB to GB: 1TB=1024GB, 1PB=1048576GB)
     IMPORTANT for vector workloads: "size" is ONLY the document/metadata storage, NOT vector memory.
     The calculator computes vector memory automatically from vectorCount × dimensionsCount.
     Do NOT add vector memory to the size field. If the user only specifies vectors without
     mentioning document data size, estimate metadata at 1 KB per vector
     (e.g., 10M vectors → size=10 GB, 300M vectors → size=300 GB).
   - Region: AWS region code (default: "us-east-1"). All commercial regions, China (cn-north-1, cn-northwest-1), Secret (us-isob-east-1, us-isob-west-1), and Top Secret (us-iso-east-1, us-iso-west-1) are supported.
   - Vector-specific: vectorCount, dimensionsCount (default: 384), vectorEngineType
   - TimeSeries-specific: hotRetentionPeriod, warmRetentionPeriod
     * RETENTION RULES:
       a) If retention period is NOT mentioned → Default to 14 days hot retention (hotRetentionPeriod=14, warmRetentionPeriod=0)
       b) If retention period IS mentioned without hot/warm separation (e.g., "keep 30 days", "retain for 7 days") → Treat all as hot retention
       c) If both hot and warm are specified separately → Use those values
   - Pricing: OnDemand (default) or Reserved Instance types

OUTPUT FORMAT:
Return a JSON object with this exact structure:

{
  "workloadType": "search|vector|timeSeries",
  "deploymentPreference": "managed|serverless|both",
  "environmentIntent": "production-performant|production-balanced|production-cost-optimized|dev",
  "managedRequest": {
    // Complete MCP request for provisioned_estimate tool
    "[workload]": {  // "search", "vector", or "timeSeries"
      "size": <number>,
      "region": <string>,
      "azs": <integer>,
      "replicas": <integer>,
      "CPUsPerShard": <number>,
      "minimumJVM": <number>,
      "dedicatedManager": <boolean>,
      "targetShardSize": <integer>,
      "preferInternalStorage": <boolean>,
      "storageClass": <string>,
      "freeStorageRequired": <integer>,
      "indexExpansionRate": <integer>,
      "pricingType": <string>,
      "config": "dev|production",
      "multiAzWithStandby": <boolean>  // Enable for 99.99%% availability (requires minimum 2 replicas)
      // Plus workload-specific fields
    },
    "structuredResponse": true
  },
  "serverlessRequest": {
    // Complete MCP request for serverless_v2_estimate tool
    "region": <string>,
    "ingest": {
      "minIndexingRate": <number>,
      "maxIndexingRate": <number>,
      "timePerDayAtMax": <number>,
      "replicas": <integer>
    },
    "redundancy": <boolean>,
    "structuredResponse": true
    // Plus optional workload configs (search, vector, timeSeries)
  }
}

RULES:
1. Return ONLY valid JSON, no markdown code blocks, no explanatory text
2. Apply environment intent consistently across all parameters
3. Include structuredResponse=true in both requests
4. For vector workloads, ensure onDisk and compressionLevel are compatible with engine type
5. Set reasonable defaults based on best practices
6. If deployment preference is "managed", set serverlessRequest to null
7. If deployment preference is "serverless", set managedRequest to null
8. For "both", populate both requests completely
9. For vector workloads in managed: if vectorCount and dimensionsCount are provided, set appropriate targetShardSize (45GB default)
10. For serverless ingest: estimate rates based on data size (e.g., 100GB daily ≈ 4GB/hour avg, 10GB/hour peak)

EXAMPLES:

Query: "Vector search with 10 million vectors, 768 dimensions, need high performance in us-east-1"
{
  "workloadType": "vector",
  "deploymentPreference": "both",
  "environmentIntent": "production-performant",
  "managedRequest": {
    "vector": {
      "size": 50,
      "vectorCount": 10000000,
      "dimensionsCount": 768,
      "vectorEngineType": "hnswfp16",
      "azs": 3,
      "replicas": 2,
      "CPUsPerShard": 2.0,
      "minimumJVM": 32,
      "dedicatedManager": true,
      "preferInternalStorage": true,
      "storageClass": "gp3",
      "targetShardSize": 45,
      "freeStorageRequired": 25,
      "indexExpansionRate": 25,
      "onDisk": false,
      "multiAzWithStandby": true,
      "region": "us-east-1",
      "pricingType": "OnDemand",
      "config": "production"
    },
    "structuredResponse": true
  },
  "serverlessRequest": {
    "region": "us-east-1",
    "ingest": {
      "minIndexingRate": 0.5,
      "maxIndexingRate": 2.0,
      "timePerDayAtMax": 8,
      "replicas": 1
    },
    "vector": {
      "size": 500,
      "vectorCount": 10000000,
      "dimensionsCount": 768,
      "vectorEngineType": "hnswfp16",
      "replicas": 1,
      "config": "production"
    },
    "redundancy": true,
    "structuredResponse": true
  }
}

Query: "Small dev cluster for testing logs, 100GB daily, keep 7 days"
{
  "workloadType": "timeSeries",
  "deploymentPreference": "both",
  "environmentIntent": "dev",
  "managedRequest": {
    "timeSeries": {
      "size": 100,
      "hotRetentionPeriod": 7,
      "warmRetentionPeriod": 0,
      "azs": 1,
      "replicas": 0,
      "CPUsPerShard": 1.0,
      "minimumJVM": 0,
      "dedicatedManager": false,
      "preferInternalStorage": false,
      "storageClass": "gp3",
      "targetShardSize": 45,
      "freeStorageRequired": 15,
      "indexExpansionRate": 10,
      "multiAzWithStandby": false,
      "region": "us-east-1",
      "pricingType": "OnDemand",
      "config": "dev"
    },
    "structuredResponse": true
  },
  "serverlessRequest": {
    "region": "us-east-1",
    "ingest": {
      "minIndexingRate": 0.1,
      "maxIndexingRate": 0.5,
      "timePerDayAtMax": 4,
      "replicas": 0
    },
    "timeSeries": {
      "dailyIndexSize": 100,
      "daysInHot": 7,
      "daysInWarm": 0,
      "replicas": 0
    },
    "redundancy": false,
    "structuredResponse": true
  }
}

Query: "Log analytics workload with 50GB daily data" (no retention specified)
{
  "workloadType": "timeSeries",
  "deploymentPreference": "both",
  "environmentIntent": "production-balanced",
  "managedRequest": {
    "timeSeries": {
      "size": 50,
      "hotRetentionPeriod": 14,
      "warmRetentionPeriod": 0,
      "azs": 3,
      "replicas": 1,
      "CPUsPerShard": 1.5,
      "minimumJVM": 8,
      "dedicatedManager": true,
      "preferInternalStorage": false,
      "storageClass": "gp3",
      "targetShardSize": 45,
      "freeStorageRequired": 25,
      "indexExpansionRate": 10,
      "region": "us-east-1",
      "pricingType": "OnDemand",
      "config": "production"
    },
    "structuredResponse": true
  },
  "serverlessRequest": {
    "region": "us-east-1",
    "ingest": {
      "minIndexingRate": 0.5,
      "maxIndexingRate": 2.0,
      "timePerDayAtMax": 8,
      "replicas": 1
    },
    "timeSeries": {
      "dailyIndexSize": 50,
      "daysInHot": 14,
      "daysInWarm": 0,
      "replicas": 1
    },
    "redundancy": true,
    "structuredResponse": true
  }
}

Now analyze the user query and generate the response:`

	return fmt.Sprintf(promptTemplate, query)
}

func (e *LLMEnhancer) buildComprehensivePromptWithContext(query string, history []ConversationMessage) string {
	// If no history, use the standard prompt
	if len(history) == 0 {
		return e.buildComprehensivePrompt(query)
	}

	// Build conversation history string
	conversationHistory := e.formatConversationHistory(history)

	promptTemplate := `You are an expert OpenSearch architect specializing in cost estimation and infrastructure configuration.

CONVERSATION HISTORY:
%s

CURRENT USER QUERY: "%s"

TASK:
Based on the conversation history and the current query, analyze what the user is asking for and generate complete MCP request payloads for both Managed (Provisioned) and Serverless OpenSearch deployments.

CONTEXT ANALYSIS:
1. Review the conversation history to understand:
   - What the user has asked before
   - What configurations were previously discussed
   - What the user is trying to refine or modify

2. For the CURRENT QUERY, determine if it is:
   - A CLARIFICATION question about previous results (e.g., "how did you get that number?", "why is the cost so high?", "explain the storage calculation")
     → Do NOT generate new MCP requests. Instead return: {"clarification": true, "response": "<brief explanation based on conversation history>"}
   - A follow-up question modifying previous parameters (e.g., "what about 20M vectors instead?", "try with 3 AZs")
   - A related new question building on previous context (e.g., "what if I use serverless?", "how much for production?")
   - A completely new question (start fresh with new configuration)

3. WORKLOAD IDENTIFICATION
   - If user references "that" or "it" or "the cluster", use the workload type from conversation history
   - Otherwise determine: "vector", "search", or "timeSeries"

4. ENVIRONMENT INTENT CLASSIFICATION
   Classify based on query language and history:
   - "production-performant": High performance keywords
   - "production-balanced": Production without performance emphasis (default for production)
   - "production-cost-optimized": Cost-focused production
   - "dev": Development, testing, POC keywords

5. PARAMETER EXTRACTION
   - Extract parameters from CURRENT query
   - If the current query references previous values (e.g., "same as before but..."), inherit those values
   - If modifying specific parameters (e.g., "increase to 50M vectors"), change only those parameters

OUTPUT FORMAT:
Return a JSON object with this exact structure:

{
  "workloadType": "search|vector|timeSeries",
  "deploymentPreference": "managed|serverless|both",
  "environmentIntent": "production-performant|production-balanced|production-cost-optimized|dev",
  "managedRequest": {
    // Complete MCP request for provisioned_estimate tool
  },
  "serverlessRequest": {
    // Complete MCP request for serverless_v2_estimate tool
  }
}

RULES:
1. Return ONLY valid JSON, no markdown code blocks, no explanatory text
2. Apply environment intent consistently across all parameters
3. Include structuredResponse=true in both requests
4. Use conversation history to inform parameter defaults
5. If user says "try" or "what if" or "how about", generate a complete new configuration
6. For modifications (e.g., "increase vectors to"), keep other parameters the same
7. Set deployment preference based on query ("managed only" vs "serverless only" vs "both")

Now analyze the conversation and current query to generate the response:`

	return fmt.Sprintf(promptTemplate, conversationHistory, query)
}

func (e *LLMEnhancer) formatConversationHistory(history []ConversationMessage) string {
	if len(history) == 0 {
		return "(no previous conversation)"
	}

	var formatted string
	for i, msg := range history {
		roleLabel := "User"
		if msg.Role == "assistant" {
			roleLabel = "Assistant"
		}
		formatted += fmt.Sprintf("%d. %s: %s\n", i+1, roleLabel, msg.Content)
	}

	return formatted
}

func (e *LLMEnhancer) invokeModel(ctx context.Context, prompt string) (string, error) {
	// Build request payload for Claude Opus 4.5
	requestBody := map[string]interface{}{
		"anthropic_version": "bedrock-2023-05-31",
		"max_tokens":        4000, // Increased for comprehensive responses
		"messages": []map[string]string{
			{
				"role":    "user",
				"content": prompt,
			},
		},
		"temperature": 0.1, // Low temperature for consistent extraction
	}

	requestJSON, err := json.Marshal(requestBody)
	if err != nil {
		return "", fmt.Errorf("failed to marshal request: %w", err)
	}

	input := &bedrockruntime.InvokeModelInput{
		ModelId:     aws.String(e.modelID),
		ContentType: aws.String("application/json"),
		Accept:      aws.String("application/json"),
		Body:        requestJSON,
	}

	result, err := e.client.InvokeModel(ctx, input)
	if err != nil {
		return "", fmt.Errorf("failed to invoke model: %w", err)
	}

	// Parse Claude response
	var response struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	}

	if err := json.Unmarshal(result.Body, &response); err != nil {
		return "", fmt.Errorf("failed to unmarshal response: %w", err)
	}

	if len(response.Content) == 0 {
		return "", fmt.Errorf("empty response from model")
	}

	return response.Content[0].Text, nil
}

// enhanceWithToolUse uses Bedrock's native Tool Calling API
// This is the production-ready approach with automatic parameter validation
func (e *LLMEnhancer) enhanceWithToolUse(ctx context.Context, query string, history []ConversationMessage) (*EnhancedLLMResponse, error) {
	// Build the system prompt for tool use
	systemPrompt := e.buildToolUseSystemPrompt()

	// Build conversation messages
	messages := e.buildToolUseMessages(query, history)

	// Get tool definitions from bedrock_tools.go
	tools := GetAllTools()

	// Invoke Bedrock with tool use
	response, err := e.invokeModelWithTools(ctx, systemPrompt, messages, tools)
	if err != nil {
		return nil, fmt.Errorf("failed to invoke Bedrock with tools: %w", err)
	}

	// Parse tool use response
	enhanced, err := e.parseToolUseResponse(response, query)
	if err != nil {
		return nil, fmt.Errorf("failed to parse tool use response: %w", err)
	}

	return enhanced, nil
}

// buildToolUseSystemPrompt creates the system prompt for tool use
func (e *LLMEnhancer) buildToolUseSystemPrompt() string {
	return `You are an expert OpenSearch architect specializing in cost estimation.

Your task is to analyze user queries about OpenSearch deployments and call the appropriate cost estimation tools.

ANALYSIS STEPS:

1. WORKLOAD IDENTIFICATION
   - "vector": Vector similarity search (requires vectorCount, dimensions)
   - "search": Full-text search workloads
   - "timeSeries": Time-series/log analytics data

2. ENVIRONMENT INTENT CLASSIFICATION
   - "production-performant": High performance, low latency → 3 AZs, 2 replicas, 2.0 CPUs, 32GB JVM
   - "production-balanced": Standard production → 3 AZs, 1 replica, 1.5 CPUs, 8GB JVM
   - "production-cost-optimized": Budget-conscious production → 3 AZs, 1 replica, 1.0 CPUs, compression enabled
   - "dev": Development/testing → 1 AZ, 0 replicas, minimal resources

3. DEPLOYMENT PREFERENCE
   - Call provisioned_estimate for managed clusters
   - Call serverless_v2_estimate for serverless collections
   - Call BOTH tools to compare costs (default)

4. PARAMETER EXTRACTION
   - Extract data size (convert TB/PB to GB: 1TB=1024GB, 1PB=1048576GB)
   - Extract region (default: us-east-1)
   - Vector: vectorCount, dimensionsCount (default: 384), vectorEngineType
   - TimeSeries: hotRetentionPeriod (default: 14 if not specified), warmRetentionPeriod

IMPORTANT RULES:
- Always call tools with complete, production-ready parameters
- Apply environment intent to all configuration parameters consistently
- For vector workloads, prefer onDisk=true with compressionLevel=32 for cost optimization
- For production workloads, always use dedicatedManager=true and 3 AZs
- For dev workloads, minimize resources (1 AZ, 0 replicas, no dedicated manager)
- Include structuredResponse=true in all tool calls`
}

// buildToolUseMessages converts conversation history and current query into Bedrock message format
func (e *LLMEnhancer) buildToolUseMessages(query string, history []ConversationMessage) []map[string]interface{} {
	messages := []map[string]interface{}{}

	// Add conversation history
	for _, msg := range history {
		messages = append(messages, map[string]interface{}{
			"role": msg.Role,
			"content": []map[string]interface{}{
				{"type": "text", "text": msg.Content},
			},
		})
	}

	// Add current query
	messages = append(messages, map[string]interface{}{
		"role": "user",
		"content": []map[string]interface{}{
			{"type": "text", "text": query},
		},
	})

	return messages
}

// invokeModelWithTools calls Bedrock with tool use capability
func (e *LLMEnhancer) invokeModelWithTools(ctx context.Context, systemPrompt string, messages []map[string]interface{}, tools []map[string]interface{}) (map[string]interface{}, error) {
	// Build request payload for Claude Opus 4.5 with tool use
	requestBody := map[string]interface{}{
		"anthropic_version": "bedrock-2023-05-31",
		"max_tokens":        4000,
		"system":            systemPrompt,
		"messages":          messages,
		"tools":             tools,
		"temperature":       0.1, // Low temperature for consistent parameter extraction
	}

	requestJSON, err := json.Marshal(requestBody)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal request: %w", err)
	}

	input := &bedrockruntime.InvokeModelInput{
		ModelId:     aws.String(e.modelID),
		ContentType: aws.String("application/json"),
		Accept:      aws.String("application/json"),
		Body:        requestJSON,
	}

	result, err := e.client.InvokeModel(ctx, input)
	if err != nil {
		return nil, fmt.Errorf("failed to invoke model: %w", err)
	}

	// Parse response
	var response map[string]interface{}
	if err := json.Unmarshal(result.Body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response, nil
}

// parseToolUseResponse extracts tool calls from Bedrock response
func (e *LLMEnhancer) parseToolUseResponse(response map[string]interface{}, originalQuery string) (*EnhancedLLMResponse, error) {
	// Extract content blocks
	content, ok := response["content"].([]interface{})
	if !ok || len(content) == 0 {
		return nil, fmt.Errorf("no content in Bedrock response")
	}

	enhanced := &EnhancedLLMResponse{
		RawQuery:             originalQuery,
		Confidence:           0.95,   // High confidence for tool use
		DeploymentPreference: "both", // Default
	}

	// Process each content block
	for _, block := range content {
		blockMap, ok := block.(map[string]interface{})
		if !ok {
			continue
		}

		blockType, _ := blockMap["type"].(string)
		if blockType != "tool_use" {
			continue
		}

		// Extract tool name and input
		toolName, _ := blockMap["name"].(string)
		toolInput, ok := blockMap["input"].(map[string]interface{})
		if !ok {
			continue
		}

		// Add structuredResponse flag
		toolInput["structuredResponse"] = true

		// Route to appropriate request
		switch toolName {
		case "provisioned_estimate":
			enhanced.ManagedRequest = toolInput
			// Extract workload type from managed request
			if enhanced.WorkloadType == "" {
				enhanced.WorkloadType = e.extractWorkloadType(toolInput)
			}

		case "serverless_v2_estimate":
			enhanced.ServerlessRequest = toolInput
			// Extract workload type from serverless request if not set
			if enhanced.WorkloadType == "" {
				enhanced.WorkloadType = e.extractWorkloadTypeFromServerless(toolInput)
			}
		}
	}

	// Validate that we got at least one tool call
	if enhanced.ManagedRequest == nil && enhanced.ServerlessRequest == nil {
		return nil, fmt.Errorf("no valid tool calls in response")
	}

	// Set deployment preference based on which tools were called
	if enhanced.ManagedRequest != nil && enhanced.ServerlessRequest != nil {
		enhanced.DeploymentPreference = "both"
	} else if enhanced.ManagedRequest != nil {
		enhanced.DeploymentPreference = "managed"
	} else if enhanced.ServerlessRequest != nil {
		enhanced.DeploymentPreference = "serverless"
	}

	// Extract environment intent from managed request if available
	if enhanced.ManagedRequest != nil {
		enhanced.EnvironmentIntent = e.extractEnvironmentIntent(enhanced.ManagedRequest)
	}

	return enhanced, nil
}

// extractWorkloadType determines workload type from provisioned request
func (e *LLMEnhancer) extractWorkloadType(request map[string]interface{}) string {
	if _, ok := request["vector"]; ok {
		return "vector"
	}
	if _, ok := request["search"]; ok {
		return "search"
	}
	if _, ok := request["timeSeries"]; ok {
		return "timeSeries"
	}
	return ""
}

// extractWorkloadTypeFromServerless determines workload type from serverless request
func (e *LLMEnhancer) extractWorkloadTypeFromServerless(request map[string]interface{}) string {
	if _, ok := request["vector"]; ok {
		return "vector"
	}
	if _, ok := request["search"]; ok {
		return "search"
	}
	if _, ok := request["timeSeries"]; ok {
		return "timeSeries"
	}
	return "search" // Default for serverless
}

// extractEnvironmentIntent infers environment intent from configuration
func (e *LLMEnhancer) extractEnvironmentIntent(request map[string]interface{}) string {
	// Try to extract from workload config
	var workloadConfig map[string]interface{}
	for _, key := range []string{"vector", "search", "timeSeries"} {
		if config, ok := request[key].(map[string]interface{}); ok {
			workloadConfig = config
			break
		}
	}

	if workloadConfig == nil {
		return "production-balanced"
	}

	// Infer from configuration parameters
	azs, _ := workloadConfig["azs"].(float64)
	replicas, _ := workloadConfig["replicas"].(float64)
	cpus, _ := workloadConfig["CPUsPerShard"].(float64)

	// Dev: 1 AZ, 0 replicas
	if azs == 1 && replicas == 0 {
		return "dev"
	}

	// Production-performant: 3 AZs, 2 replicas, 2.0 CPUs
	if azs == 3 && replicas == 2 && cpus >= 2.0 {
		return "production-performant"
	}

	// Production-cost-optimized: 3 AZs, 1 replica, 1.0 CPUs
	if azs == 3 && replicas == 1 && cpus == 1.0 {
		return "production-cost-optimized"
	}

	// Default: production-balanced
	return "production-balanced"
}

func (e *LLMEnhancer) parseEnhancedResponse(response string, originalQuery string) (*EnhancedLLMResponse, error) {
	// Clean response - remove markdown code blocks if present
	cleanedResponse := strings.TrimSpace(response)

	// Remove ```json and ``` if present
	if strings.HasPrefix(cleanedResponse, "```json") {
		cleanedResponse = strings.TrimPrefix(cleanedResponse, "```json")
		cleanedResponse = strings.TrimSpace(cleanedResponse)
	}
	if strings.HasPrefix(cleanedResponse, "```") {
		cleanedResponse = strings.TrimPrefix(cleanedResponse, "```")
		cleanedResponse = strings.TrimSpace(cleanedResponse)
	}
	if strings.HasSuffix(cleanedResponse, "```") {
		cleanedResponse = strings.TrimSuffix(cleanedResponse, "```")
		cleanedResponse = strings.TrimSpace(cleanedResponse)
	}

	// Parse JSON response — first check if it's a clarification response
	var rawResponse map[string]interface{}
	if err := json.Unmarshal([]byte(cleanedResponse), &rawResponse); err != nil {
		return nil, fmt.Errorf("failed to parse LLM JSON response: %w (response: %s)", err, cleanedResponse)
	}

	// Handle clarification responses (non-estimation follow-ups)
	if isClarification, _ := rawResponse["clarification"].(bool); isClarification {
		clarificationText, _ := rawResponse["response"].(string)
		if clarificationText == "" {
			clarificationText = "I can help clarify. Could you rephrase your question?"
		}
		return &EnhancedLLMResponse{
			RawQuery:      originalQuery,
			Confidence:    0.95,
			Clarification: clarificationText,
		}, nil
	}

	var enhanced EnhancedLLMResponse
	if err := json.Unmarshal([]byte(cleanedResponse), &enhanced); err != nil {
		return nil, fmt.Errorf("failed to parse LLM JSON response: %w (response: %s)", err, cleanedResponse)
	}

	// Validate required fields
	if enhanced.WorkloadType == "" {
		return nil, fmt.Errorf("missing workloadType in LLM response")
	}
	if enhanced.DeploymentPreference == "" {
		enhanced.DeploymentPreference = "both" // Default
	}
	if enhanced.EnvironmentIntent == "" {
		enhanced.EnvironmentIntent = "production-balanced" // Default
	}

	// Set confidence based on completeness
	enhanced.Confidence = 0.95 // High confidence for LLM-generated complete requests
	enhanced.RawQuery = originalQuery

	// Validate MCP requests are present based on deployment preference
	if enhanced.DeploymentPreference == "managed" || enhanced.DeploymentPreference == "both" {
		if len(enhanced.ManagedRequest) == 0 {
			return nil, fmt.Errorf("missing managedRequest for deployment preference: %s", enhanced.DeploymentPreference)
		}
	}
	if enhanced.DeploymentPreference == "serverless" || enhanced.DeploymentPreference == "both" {
		if len(enhanced.ServerlessRequest) == 0 {
			return nil, fmt.Errorf("missing serverlessRequest for deployment preference: %s", enhanced.DeploymentPreference)
		}
	}

	return &enhanced, nil
}
