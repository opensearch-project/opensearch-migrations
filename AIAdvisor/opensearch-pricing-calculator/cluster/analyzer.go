// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

// Package cluster provides source cluster analysis for generating cost estimates.
// It connects to Elasticsearch or OpenSearch clusters, extracts metrics, and maps
// them to pricing calculator input parameters.
package cluster

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"strings"
	"time"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
)

// Analyzer connects to a source cluster and extracts sizing information.
type Analyzer struct {
	client     *http.Client
	clusterURL string
	username   string
	password   string
}

// NewAnalyzer creates a new cluster analyzer.
func NewAnalyzer(clusterURL, username, password string, insecure bool) *Analyzer {
	transport := &http.Transport{}
	if insecure {
		transport.TLSClientConfig = &tls.Config{InsecureSkipVerify: true} //nolint:gosec // user-requested for self-signed certs
	}

	return &Analyzer{
		client: &http.Client{
			Timeout:   30 * time.Second,
			Transport: transport,
		},
		clusterURL: strings.TrimRight(clusterURL, "/"),
		username:   username,
		password:   password,
	}
}

// ClusterMetrics holds the extracted cluster information.
type ClusterMetrics struct {
	ClusterName   string  `json:"clusterName"`
	Version       string  `json:"version"`
	NodeCount     int     `json:"nodeCount"`
	DataNodeCount int     `json:"dataNodeCount"`
	TotalDataGB   float64 `json:"totalDataGB"`
	IndexCount    int     `json:"indexCount"`
	ShardCount    int     `json:"shardCount"`
	ReplicaCount  int     `json:"replicaCount"`
	WorkloadType  string  `json:"workloadType"`
}

// Analyze connects to the cluster and collects metrics.
func (a *Analyzer) Analyze() (*ClusterMetrics, error) {
	metrics := &ClusterMetrics{}

	// Get cluster info (version)
	info, err := a.getClusterInfo()
	if err != nil {
		return nil, fmt.Errorf("failed to get cluster info: %w", err)
	}
	metrics.ClusterName = info.ClusterName
	metrics.Version = info.Version.Number

	// Get cluster stats
	stats, err := a.getClusterStats()
	if err != nil {
		return nil, fmt.Errorf("failed to get cluster stats: %w", err)
	}
	metrics.NodeCount = stats.Nodes.Count.Total
	metrics.DataNodeCount = stats.Nodes.Count.Data
	metrics.IndexCount = stats.Indices.Count
	metrics.ShardCount = stats.Indices.Shards.Total
	metrics.TotalDataGB = float64(stats.Indices.Store.SizeInBytes) / (1024 * 1024 * 1024)

	// Infer replica count from primary vs total shards
	if stats.Indices.Shards.Primaries > 0 {
		metrics.ReplicaCount = (stats.Indices.Shards.Total / stats.Indices.Shards.Primaries) - 1
	}

	// Infer workload type from index patterns
	metrics.WorkloadType = a.inferWorkloadType(stats)

	return metrics, nil
}

// ToEstimateRequest converts cluster metrics to a provisioned estimate request.
func (m *ClusterMetrics) ToEstimateRequest(region, pricingType string, azs int) provisioned.EstimateRequest {
	var request provisioned.EstimateRequest

	switch m.WorkloadType {
	case "timeSeries":
		tr := provisioned.GetDefaultTimeSeriesRequest()
		tr.IngestionSize = math.Ceil(m.TotalDataGB / math.Max(float64(m.ReplicaCount+1), 1))
		tr.Region = region
		tr.PricingType = pricingType
		if azs > 0 {
			tr.Azs = azs
		} else if m.DataNodeCount >= 3 {
			tr.Azs = 3
		}
		tr.Replicas = m.ReplicaCount
		request.TimeSeries = tr

	case "vector":
		vr := provisioned.GetDefaultVectorRequest()
		vr.DataSize = m.TotalDataGB
		vr.Region = region
		vr.PricingType = pricingType
		if azs > 0 {
			vr.Azs = azs
		} else if m.DataNodeCount >= 3 {
			vr.Azs = 3
		}
		vr.Replicas = m.ReplicaCount
		request.Vector = vr

	default: // "search"
		sr := provisioned.GetDefaultSearchRequest()
		sr.DataSize = m.TotalDataGB
		sr.Region = region
		sr.PricingType = pricingType
		if azs > 0 {
			sr.Azs = azs
		} else if m.DataNodeCount >= 3 {
			sr.Azs = 3
		}
		sr.Replicas = m.ReplicaCount
		request.Search = sr
	}

	return request
}

// clusterInfo represents the response from GET /
type clusterInfo struct {
	ClusterName string      `json:"cluster_name"`
	Version     versionInfo `json:"version"`
}

type versionInfo struct {
	Number       string `json:"number"`
	Distribution string `json:"distribution"`
}

// clusterStats represents the response from GET /_cluster/stats
type clusterStats struct {
	Indices clusterIndicesStats `json:"indices"`
	Nodes   clusterNodesStats   `json:"nodes"`
}

type clusterIndicesStats struct {
	Count     int              `json:"count"`
	Shards    clusterShards    `json:"shards"`
	Store     clusterStore     `json:"store"`
	Fielddata clusterFielddata `json:"fielddata"`
}

type clusterShards struct {
	Total     int `json:"total"`
	Primaries int `json:"primaries"`
}

type clusterStore struct {
	SizeInBytes int64 `json:"size_in_bytes"`
}

type clusterFielddata struct {
	MemorySizeInBytes int64 `json:"memory_size_in_bytes"`
}

type clusterNodesStats struct {
	Count nodeCount `json:"count"`
}

type nodeCount struct {
	Total int `json:"total"`
	Data  int `json:"data"`
}

func (a *Analyzer) doRequest(path string) ([]byte, error) {
	req, err := http.NewRequest("GET", a.clusterURL+path, nil)
	if err != nil {
		return nil, err
	}

	if a.username != "" {
		req.SetBasicAuth(a.username, a.password)
	}
	req.Header.Set("Accept", "application/json")

	resp, err := a.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request to %s failed: %w", path, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("request to %s returned %d: %s", path, resp.StatusCode, string(body))
	}

	return io.ReadAll(resp.Body)
}

func (a *Analyzer) getClusterInfo() (*clusterInfo, error) {
	body, err := a.doRequest("/")
	if err != nil {
		return nil, err
	}
	var info clusterInfo
	if err := json.Unmarshal(body, &info); err != nil {
		return nil, fmt.Errorf("failed to parse cluster info: %w", err)
	}
	return &info, nil
}

func (a *Analyzer) getClusterStats() (*clusterStats, error) {
	body, err := a.doRequest("/_cluster/stats")
	if err != nil {
		return nil, err
	}
	var stats clusterStats
	if err := json.Unmarshal(body, &stats); err != nil {
		return nil, fmt.Errorf("failed to parse cluster stats: %w", err)
	}
	return &stats, nil
}

// inferWorkloadType examines cluster stats to determine the likely workload type.
// Heuristic: checks for time-series patterns (date-based indices) and vector workloads
// (high fielddata memory relative to store size, suggesting k-NN indices).
func (a *Analyzer) inferWorkloadType(stats *clusterStats) string {
	// Check for vector workload: high fielddata/memory ratio suggests k-NN
	if stats.Indices.Store.SizeInBytes > 0 && stats.Indices.Fielddata.MemorySizeInBytes > 0 {
		ratio := float64(stats.Indices.Fielddata.MemorySizeInBytes) / float64(stats.Indices.Store.SizeInBytes)
		if ratio > 0.3 {
			return "vector"
		}
	}

	// Check for time-series: look for date-based index patterns
	indices, err := a.getIndexNames()
	if err == nil {
		datePatternCount := 0
		for _, name := range indices {
			// Common time-series patterns: logs-2024.01.15, metrics-2024-01, filebeat-7.x-2024.01.15
			if containsDatePattern(name) {
				datePatternCount++
			}
		}
		if len(indices) > 0 && float64(datePatternCount)/float64(len(indices)) > 0.5 {
			return "timeSeries"
		}
	}

	return "search"
}

func (a *Analyzer) getIndexNames() ([]string, error) {
	body, err := a.doRequest("/_cat/indices?format=json&h=index")
	if err != nil {
		return nil, err
	}
	var indices []struct {
		Index string `json:"index"`
	}
	if err := json.Unmarshal(body, &indices); err != nil {
		return nil, err
	}
	names := make([]string, len(indices))
	for i, idx := range indices {
		names[i] = idx.Index
	}
	return names, nil
}

// containsDatePattern checks if an index name contains a date-like pattern.
func containsDatePattern(name string) bool {
	// Match patterns like: 2024.01.15, 2024-01-15, 2024.01, 2024-01
	for i := 0; i < len(name)-3; i++ {
		if name[i] >= '2' && name[i] <= '2' && // starts with '2' (2xxx year)
			name[i+1] >= '0' && name[i+1] <= '9' &&
			name[i+2] >= '0' && name[i+2] <= '9' &&
			name[i+3] >= '0' && name[i+3] <= '9' {
			return true
		}
	}
	return false
}
