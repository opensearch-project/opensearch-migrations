// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cluster

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAnalyze(t *testing.T) {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]interface{}{
			"cluster_name": "test-cluster",
			"version":      map[string]string{"number": "2.11.0", "distribution": "opensearch"},
		})
	})

	mux.HandleFunc("/_cluster/stats", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]interface{}{
			"indices": map[string]interface{}{
				"count": 10,
				"shards": map[string]int{
					"total":    20,
					"primaries": 10,
				},
				"store": map[string]int64{
					"size_in_bytes": 107374182400, // 100 GB
				},
				"fielddata": map[string]int64{
					"memory_size_in_bytes": 0,
				},
			},
			"nodes": map[string]interface{}{
				"count": map[string]int{
					"total": 6,
					"data":  3,
				},
			},
		})
	})

	mux.HandleFunc("/_cat/indices", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode([]map[string]string{
			{"index": "products"},
			{"index": "users"},
			{"index": "orders"},
		})
	})

	server := httptest.NewServer(mux)
	defer server.Close()

	analyzer := NewAnalyzer(server.URL, "", "", false)
	metrics, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	if metrics.ClusterName != "test-cluster" {
		t.Errorf("ClusterName = %q, want %q", metrics.ClusterName, "test-cluster")
	}
	if metrics.Version != "2.11.0" {
		t.Errorf("Version = %q, want %q", metrics.Version, "2.11.0")
	}
	if metrics.NodeCount != 6 {
		t.Errorf("NodeCount = %d, want 6", metrics.NodeCount)
	}
	if metrics.DataNodeCount != 3 {
		t.Errorf("DataNodeCount = %d, want 3", metrics.DataNodeCount)
	}
	if metrics.TotalDataGB < 99 || metrics.TotalDataGB > 101 {
		t.Errorf("TotalDataGB = %.1f, want ~100", metrics.TotalDataGB)
	}
	if metrics.IndexCount != 10 {
		t.Errorf("IndexCount = %d, want 10", metrics.IndexCount)
	}
	if metrics.ShardCount != 20 {
		t.Errorf("ShardCount = %d, want 20", metrics.ShardCount)
	}
	if metrics.ReplicaCount != 1 {
		t.Errorf("ReplicaCount = %d, want 1", metrics.ReplicaCount)
	}
	if metrics.WorkloadType != "search" {
		t.Errorf("WorkloadType = %q, want %q", metrics.WorkloadType, "search")
	}
}

func TestAnalyze_TimeSeries(t *testing.T) {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]interface{}{
			"cluster_name": "logs-cluster",
			"version":      map[string]string{"number": "7.10.2"},
		})
	})

	mux.HandleFunc("/_cluster/stats", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]interface{}{
			"indices": map[string]interface{}{
				"count":  30,
				"shards": map[string]int{"total": 60, "primaries": 30},
				"store":  map[string]int64{"size_in_bytes": 536870912000},
				"fielddata": map[string]int64{"memory_size_in_bytes": 0},
			},
			"nodes": map[string]interface{}{
				"count": map[string]int{"total": 6, "data": 3},
			},
		})
	})

	mux.HandleFunc("/_cat/indices", func(w http.ResponseWriter, r *http.Request) {
		// Majority of indices have date patterns
		indices := []map[string]string{
			{"index": "logs-2024.01.01"},
			{"index": "logs-2024.01.02"},
			{"index": "logs-2024.01.03"},
			{"index": "metrics-2024-01"},
			{"index": "metrics-2024-02"},
			{"index": "config"},
		}
		json.NewEncoder(w).Encode(indices)
	})

	server := httptest.NewServer(mux)
	defer server.Close()

	analyzer := NewAnalyzer(server.URL, "", "", false)
	metrics, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	if metrics.WorkloadType != "timeSeries" {
		t.Errorf("WorkloadType = %q, want %q", metrics.WorkloadType, "timeSeries")
	}
}

func TestToEstimateRequest(t *testing.T) {
	metrics := &ClusterMetrics{
		TotalDataGB:   500,
		DataNodeCount: 3,
		ReplicaCount:  1,
		WorkloadType:  "search",
	}

	request := metrics.ToEstimateRequest("US East (N. Virginia)", "OnDemand", 0)

	if request.Search == nil {
		t.Fatal("expected Search request, got nil")
	}
	if request.Search.DataSize != 500 {
		t.Errorf("DataSize = %.1f, want 500", request.Search.DataSize)
	}
	if request.Search.Azs != 3 {
		t.Errorf("Azs = %d, want 3 (auto-detected from 3 data nodes)", request.Search.Azs)
	}
	if request.Search.Replicas != 1 {
		t.Errorf("Replicas = %d, want 1", request.Search.Replicas)
	}
}

func TestContainsDatePattern(t *testing.T) {
	tests := []struct {
		name     string
		expected bool
	}{
		{"logs-2024.01.15", true},
		{"metrics-2024-01", true},
		{"filebeat-7.x-2024.01.15", true},
		{"products", false},
		{"users", false},
		{".kibana", false},
	}

	for _, tt := range tests {
		if got := containsDatePattern(tt.name); got != tt.expected {
			t.Errorf("containsDatePattern(%q) = %v, want %v", tt.name, got, tt.expected)
		}
	}
}
