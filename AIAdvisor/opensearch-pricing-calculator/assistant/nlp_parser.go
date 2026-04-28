// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"regexp"
	"strconv"
	"strings"
)

// NLPParser handles natural language query parsing
type NLPParser struct {
	workloadPatterns   map[string]*regexp.Regexp
	sizePatterns       *regexp.Regexp
	regionPatterns     *regexp.Regexp
	vectorPatterns     *vectorPatterns
	deploymentPatterns map[string]*regexp.Regexp
	periodPatterns     *periodPatterns
	standbyPatterns    *regexp.Regexp
}

type vectorPatterns struct {
	count      *regexp.Regexp
	dimensions *regexp.Regexp
	engine     *regexp.Regexp
}

type periodPatterns struct {
	hot     *regexp.Regexp
	warm    *regexp.Regexp
	general *regexp.Regexp // Matches retention without hot/warm specification
}

// NewNLPParser creates a new NLP parser instance
func NewNLPParser() *NLPParser {
	return &NLPParser{
		workloadPatterns: map[string]*regexp.Regexp{
			"vector":     regexp.MustCompile(`(?i)(vector\s+search|vector|embedding|similarity\s+search|knn|ann)`),
			"search":     regexp.MustCompile(`(?i)(search|full[\s-]?text|query|elasticsearch)`),
			"timeseries": regexp.MustCompile(`(?i)(time[\s-]?series|log|logs|logging|metrics|monitoring|observability)`),
		},
		sizePatterns:   regexp.MustCompile(`(?i)(\d+(?:\.\d+)?)\s*(gb|tb|pb|gib|tib|pib)`),
		regionPatterns: regexp.MustCompile(`(?i)(us[\s-]?east[\s-]?[12]|us[\s-]?west[\s-]?[12]|eu[\s-]?west[\s-]?[123]|eu[\s-]?central[\s-]?[12]|eu[\s-]?north[\s-]?1|eu[\s-]?south[\s-]?[12]|ap[\s-]?southeast[\s-]?[1234]|ap[\s-]?northeast[\s-]?[123]|ap[\s-]?south[\s-]?[12]|ap[\s-]?east[\s-]?1|sa[\s-]?east[\s-]?1|ca[\s-]?central[\s-]?1|ca[\s-]?west[\s-]?1|me[\s-]?south[\s-]?1|me[\s-]?central[\s-]?1|af[\s-]?south[\s-]?1|il[\s-]?central[\s-]?1|cn[\s-]?north[\s-]?1|cn[\s-]?northwest[\s-]?1|us[\s-]?iso[\s-]?east[\s-]?1|us[\s-]?iso[\s-]?west[\s-]?1|us[\s-]?isob[\s-]?east[\s-]?1|us[\s-]?isob[\s-]?west[\s-]?1)`),
		vectorPatterns: &vectorPatterns{
			count:      regexp.MustCompile(`(?i)(\d+(?:\.\d+)?)\s*(million|m|billion|b|k|thousand)?\s*vectors?`),
			dimensions: regexp.MustCompile(`(?i)(\d+)\s*(?:dim(?:ension)?s?|d)`),
			engine:     regexp.MustCompile(`(?i)(hnsw(?:fp16|int8|bv|pq)?|nmslib|ivf(?:fp16|int8|bv|pq)?|exact\s*knn|faiss|s3\s*vector(?:\s*engine)?|s3)`),
		},
		deploymentPatterns: map[string]*regexp.Regexp{
			"managed":    regexp.MustCompile(`(?i)(managed|provisioned|dedicated|cluster)`),
			"serverless": regexp.MustCompile(`(?i)(serverless|on[\s-]?demand|auto[\s-]?scaling)`),
		},
		periodPatterns: &periodPatterns{
			hot:     regexp.MustCompile(`(?i)(\d+)\s*(?:days?|d)\s*(?:in\s+)?(?:hot)`),
			warm:    regexp.MustCompile(`(?i)(\d+)\s*(?:days?|d)\s*(?:in\s+)?(?:warm|cold)`),
			general: regexp.MustCompile(`(?i)(?:keep|retain|retention|store)\s+(?:for\s+)?(\d+)\s*(?:days?|d)`),
		},
		// Standby patterns: matches "standby", "multi-az with standby", "99.99%", "highest availability", etc.
		standbyPatterns: regexp.MustCompile(`(?i)(with\s+standby|standby\s+az|multi[\s-]?az\s+with\s+standby|99\.99\s*%|highest\s+availability|maximum\s+availability|four\s+nines)`),
	}
}

// Parse extracts parameters from natural language query
func (p *NLPParser) Parse(query string) (*ParsedQuery, error) {
	query = strings.TrimSpace(query)

	result := &ParsedQuery{
		RawQuery:   query,
		Confidence: 1.0,
		Region:     "us-east-1", // default
	}

	// Detect workload type
	result.WorkloadType = p.detectWorkloadType(query)
	if result.WorkloadType == "" {
		result.Confidence *= 0.5
		result.WorkloadType = "search" // default
	}

	// Extract size
	size := p.extractSize(query)
	if size > 0 {
		result.Size = size
	} else {
		result.Confidence *= 0.7
	}

	// Extract region
	if region := p.extractRegion(query); region != "" {
		result.Region = region
	}

	// Extract vector-specific parameters
	if result.WorkloadType == "vector" {
		vectorCount := p.extractVectorCount(query)
		if vectorCount > 0 {
			result.VectorCount = vectorCount
		} else {
			result.Confidence *= 0.6
		}

		dimensions := p.extractDimensions(query)
		if dimensions > 0 {
			result.Dimensions = dimensions
		} else {
			result.Confidence *= 0.6
			result.Dimensions = 768 // default
		}

		if engine := p.extractVectorEngine(query); engine != "" {
			result.VectorEngine = engine
		} else {
			result.VectorEngine = "hnswfp16" // default - most common/efficient algorithm
		}
	}

	// Extract deployment preference
	result.DeploymentPreference = p.detectDeploymentPreference(query)

	// Extract retention periods
	hotPeriod := p.extractHotPeriod(query)
	warmPeriod := p.extractWarmPeriod(query)

	if hotPeriod > 0 {
		result.HotPeriod = hotPeriod
	}
	if warmPeriod > 0 {
		result.WarmPeriod = warmPeriod
	}

	// If no hot/warm period found, try general retention pattern (assume all hot)
	if hotPeriod == 0 && warmPeriod == 0 {
		if generalPeriod := p.extractGeneralPeriod(query); generalPeriod > 0 {
			result.HotPeriod = generalPeriod
		}
	}

	// For timeseries workloads, if no retention period specified at all, default to 14 days hot
	if result.WorkloadType == "timeseries" && result.HotPeriod == 0 && result.WarmPeriod == 0 {
		result.HotPeriod = 14 // Default: 14 days hot retention
	}

	// Extract Multi-AZ with Standby preference
	result.MultiAzWithStandby = p.detectStandby(query)

	// Determine intent
	result.Intent = p.determineIntent(query)

	return result, nil
}

func (p *NLPParser) detectWorkloadType(query string) string {
	// Priority: vector > timeseries > search
	if p.workloadPatterns["vector"].MatchString(query) {
		return "vector"
	}
	if p.workloadPatterns["timeseries"].MatchString(query) {
		return "timeseries"
	}
	if p.workloadPatterns["search"].MatchString(query) {
		return "search"
	}
	return ""
}

func (p *NLPParser) extractSize(query string) int {
	matches := p.sizePatterns.FindStringSubmatch(query)
	if len(matches) < 3 {
		return 0
	}

	size, err := strconv.ParseFloat(matches[1], 64)
	if err != nil {
		return 0
	}

	// Convert to GB
	unit := strings.ToLower(matches[2])
	switch unit {
	case "tb", "tib":
		size *= 1024
	case "pb", "pib":
		size *= 1024 * 1024
	}

	return int(size)
}

func (p *NLPParser) extractRegion(query string) string {
	matches := p.regionPatterns.FindStringSubmatch(query)
	if len(matches) < 2 {
		return ""
	}

	// Normalize region format
	region := strings.ToLower(matches[1])
	region = strings.ReplaceAll(region, " ", "-")
	return region
}

func (p *NLPParser) extractVectorCount(query string) int {
	matches := p.vectorPatterns.count.FindStringSubmatch(query)
	if len(matches) < 2 {
		return 0
	}

	count, err := strconv.ParseFloat(matches[1], 64)
	if err != nil {
		return 0
	}

	// Apply multiplier
	if len(matches) > 2 && matches[2] != "" {
		multiplier := strings.ToLower(matches[2])
		switch multiplier {
		case "k", "thousand":
			count *= 1000
		case "m", "million":
			count *= 1000000
		case "b", "billion":
			count *= 1000000000
		}
	}

	return int(count)
}

func (p *NLPParser) extractDimensions(query string) int {
	matches := p.vectorPatterns.dimensions.FindStringSubmatch(query)
	if len(matches) < 2 {
		return 0
	}

	dims, err := strconv.Atoi(matches[1])
	if err != nil {
		return 0
	}

	return dims
}

func (p *NLPParser) extractVectorEngine(query string) string {
	matches := p.vectorPatterns.engine.FindStringSubmatch(query)
	if len(matches) < 2 {
		return ""
	}

	engine := strings.ToLower(matches[1])
	// Normalize spacing for "exact knn"
	engine = strings.ReplaceAll(engine, " ", "")

	// Map common names to our vector algorithm types
	switch engine {
	// HNSW variants
	case "hnsw":
		return "hnsw"
	case "hnswfp16":
		return "hnswfp16"
	case "hnswint8":
		return "hnswint8"
	case "hnswbv":
		return "hnswbv"
	case "hnswpq":
		return "hnswpq"

	// IVF variants
	case "ivf", "faiss":
		return "ivf"
	case "ivffp16":
		return "ivffp16"
	case "ivfint8":
		return "ivfint8"
	case "ivfbv":
		return "ivfbv"
	case "ivfpq":
		return "ivfpq"

	// S3 vector engine
	case "s3", "s3vector", "s3vectorengine":
		return "s3"

	// Other algorithms
	case "nmslib":
		return "nmslib"
	case "exactknn":
		return "exactknn"

	default:
		return "" // Return empty to use default
	}
}

func (p *NLPParser) detectDeploymentPreference(query string) string {
	if p.deploymentPatterns["serverless"].MatchString(query) {
		return "serverless"
	}
	if p.deploymentPatterns["managed"].MatchString(query) {
		return "managed"
	}
	return "both" // default: show both options
}

// detectStandby checks if the query mentions Multi-AZ with Standby configuration
// Matches patterns like "with standby", "standby az", "multi-az with standby",
// "99.99%", "highest availability", "maximum availability", "four nines"
func (p *NLPParser) detectStandby(query string) bool {
	return p.standbyPatterns.MatchString(query)
}

func (p *NLPParser) extractHotPeriod(query string) int {
	matches := p.periodPatterns.hot.FindStringSubmatch(query)
	if len(matches) < 2 {
		return 0
	}

	days, err := strconv.Atoi(matches[1])
	if err != nil {
		return 0
	}

	return days
}

func (p *NLPParser) extractWarmPeriod(query string) int {
	matches := p.periodPatterns.warm.FindStringSubmatch(query)
	if len(matches) < 2 {
		return 0
	}

	days, err := strconv.Atoi(matches[1])
	if err != nil {
		return 0
	}

	return days
}

func (p *NLPParser) extractGeneralPeriod(query string) int {
	matches := p.periodPatterns.general.FindStringSubmatch(query)
	if len(matches) < 2 {
		return 0
	}

	days, err := strconv.Atoi(matches[1])
	if err != nil {
		return 0
	}

	return days
}

func (p *NLPParser) determineIntent(query string) string {
	lowerQuery := strings.ToLower(query)

	if strings.Contains(lowerQuery, "cost") || strings.Contains(lowerQuery, "price") ||
		strings.Contains(lowerQuery, "how much") || strings.Contains(lowerQuery, "estimate") {
		return "cost_estimation"
	}

	if strings.Contains(lowerQuery, "recommend") || strings.Contains(lowerQuery, "suggest") ||
		strings.Contains(lowerQuery, "best") || strings.Contains(lowerQuery, "optimal") {
		return "recommendation"
	}

	if strings.Contains(lowerQuery, "compare") || strings.Contains(lowerQuery, "vs") ||
		strings.Contains(lowerQuery, "difference") {
		return "comparison"
	}

	return "general_query"
}
