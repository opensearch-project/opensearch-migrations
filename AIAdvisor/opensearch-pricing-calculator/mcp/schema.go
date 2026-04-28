// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package mcp

// Tool descriptions with exploration strategy guidance (Decision 10, 17)

const ProvisionedToolDescription = `Calculate cost estimates for provisioned Amazon OpenSearch Service domains. Supports search, vector search (HNSW, IVF with compression, S3-backed exact KNN), and time-series workloads with hot/warm/cold storage tiers. Returns cluster configurations with detailed cost breakdowns.

Environment tiers: Use config="production" or config="dev" as presets. For fine-grained control:
- Production Performant: azs=3, replicas=2, CPUsPerShard=2, dedicatedManager=true, preferInternalStorage=true, minimumJVM=32
- Production Balanced: azs=3, replicas=1, CPUsPerShard=1.5, dedicatedManager=true
- Production Cost-optimized: azs=3, replicas=1, CPUsPerShard=1.0, dedicatedManager=true
- Non-production/Dev: azs=1, replicas=0, CPUsPerShard=1, dedicatedManager=false

Exploration strategies — call multiple times with different parameters to compare:
- Storage vs Performance: CPUsPerShard=0 (storage-focused) vs CPUsPerShard=2 (performance-focused)
- Vector workloads: onDisk=true,compressionLevel=32 vs exactKNN=true
- Log analytics: vary hotRetentionPeriod, favor instanceFamily=["Storage optimized"]
- Cost tiers: compare pricingType=OnDemand vs pricingType=AURI1 (1yr reserved)`

const ServerlessToolDescription = `Calculate cost estimates for serverless v2 Amazon OpenSearch Service collections. Uses OpenSearch Compute Units (OCUs) for scaling. Requires ingest configuration; search, timeSeries, and vector are optional.

Exploration strategies — call multiple times to compare:
- Redundancy: redundancy=true (multi-AZ, production) vs redundancy=false (single-AZ, dev)
- Vector engines: compare hnsw vs hnswfp16 vs ivfpq for different accuracy/cost tradeoffs
- Compression: derivedSource=true + zstdCompression=true for maximum storage reduction`

// ProvisionedRequestSchema is the hand-crafted JSON schema for the provisioned_estimate tool.
// Per-field descriptions are slimmed to purpose + default + valid values.
// Production-tier guidance lives in ProvisionedToolDescription above.
var ProvisionedRequestSchema = map[string]interface{}{
	"type": "object",
	"properties": map[string]interface{}{
		"maxConfigurations": map[string]interface{}{"type": "integer", "description": "Number of cluster configurations to return (default: 1, max: 3). Use 1 when comparing across multiple tool calls."},
		"search": map[string]interface{}{
			"type":        "object",
			"description": "Search workload estimation",
			"properties": map[string]interface{}{
				"size":                   map[string]interface{}{"type": "number", "description": "Data size in GB"},
				"targetShardSize":        map[string]interface{}{"type": "integer", "description": "Target shard size in GB (default: 25)"},
				"azs":                    map[string]interface{}{"type": "integer", "description": "Availability zones: 1, 2, or 3 (default: 3)"},
				"replicas":               map[string]interface{}{"type": "integer", "description": "Replica count: 0, 1, or 2 (default: 1)"},
				"CPUsPerShard":           map[string]interface{}{"type": "number", "description": "CPU cores per shard (default: 1.5). Set to 0 for storage-focused sizing."},
				"region":                 map[string]interface{}{"type": "string", "description": "AWS region code (default: us-east-1). Supports commercial, China (cn-north-1, cn-northwest-1), Secret, and Top Secret regions."},
				"pricingType":            map[string]interface{}{"type": "string", "description": "OnDemand, AURI1, AURI3, NURI1, NURI3, PURI1, PURI3"},
				"preferInternalStorage":  map[string]interface{}{"type": "boolean", "description": "Prefer instance store (NVMe) over EBS (default: false)"},
				"storageClass":           map[string]interface{}{"type": "string", "description": "EBS type: gp3 (default) or NVME"},
				"freeStorageRequired":    map[string]interface{}{"type": "integer", "description": "Free storage percentage (default: 25)"},
				"indexExpansionRate":     map[string]interface{}{"type": "integer", "description": "Index expansion rate percentage (default: 10)"},
				"derivedSource":          map[string]interface{}{"type": "boolean", "description": "Enable derived source for ~25% storage reduction"},
				"zstdCompression":        map[string]interface{}{"type": "boolean", "description": "Enable ZSTD codec for ~15% storage reduction"},
				"minimumJVM":             map[string]interface{}{"type": "number", "description": "Minimum JVM heap in GB (default: 0, auto-calculated)"},
				"dedicatedManager":       map[string]interface{}{"type": "boolean", "description": "Use dedicated manager nodes (default: true)"},
				"activePrimaryShards":    map[string]interface{}{"type": "integer", "description": "Override calculated primary shards (default: 0, auto)"},
				"instanceTypes":          map[string]interface{}{"type": "array", "items": map[string]interface{}{"type": "string"}, "description": "Filter to specific instance types"},
				"instanceFamily":         map[string]interface{}{"type": "array", "items": map[string]interface{}{"type": "string"}, "description": "Filter: Memory optimized, Compute optimized, Storage optimized, OpenSearch optimized"},
				"edp":                    map[string]interface{}{"type": "number", "description": "Enterprise discount percentage"},
				"config":                 map[string]interface{}{"type": "string", "description": "Preset: dev or production"},
				"multiAzWithStandby":     map[string]interface{}{"type": "boolean", "description": "Enable Multi-AZ with Standby (99.99% availability, requires replicas>=2)"},
				"warmPercentage":         map[string]interface{}{"type": "integer", "description": "Percentage of data in warm tier (0-100, default: 0)"},
				"coldPercentage":         map[string]interface{}{"type": "integer", "description": "Percentage of data in cold/S3 tier (0-100, default: 0)"},
				"warmInstanceType":       map[string]interface{}{"type": "string", "description": "Override warm instance: ultrawarm1.medium.search, ultrawarm1.large.search, or oi2.*.search"},
				"autoSelectWarmInstance": map[string]interface{}{"type": "boolean", "description": "Auto-select warm instance type (default: true)"},
			},
		},
		"vector": map[string]interface{}{
			"type":        "object",
			"description": "Vector search workload estimation",
			"properties": map[string]interface{}{
				"size":                       map[string]interface{}{"type": "number", "description": "Data size in GB"},
				"targetShardSize":            map[string]interface{}{"type": "integer", "description": "Target shard size in GB (default: 45)"},
				"azs":                        map[string]interface{}{"type": "integer", "description": "Availability zones (default: 3)"},
				"replicas":                   map[string]interface{}{"type": "integer", "description": "Replica count (default: 1)"},
				"CPUsPerShard":               map[string]interface{}{"type": "number", "description": "CPU cores per shard (default: 1.5)"},
				"region":                     map[string]interface{}{"type": "string", "description": "AWS region code (default: us-east-1)"},
				"pricingType":                map[string]interface{}{"type": "string", "description": "OnDemand, AURI1, AURI3, NURI1, NURI3, PURI1, PURI3"},
				"vectorEngineType":           map[string]interface{}{"type": "string", "description": "hnsw, hnswfp16, hnswint8, hnswbv, ivf, ivffp16, ivfint8, ivfbv, ivfpq, hnswpq, s3. S3 engine: exact KNN via S3, requires OpenSearch Optimized instances (OR1/OR2/OM2/OI2), no hyperparameters, top_k<=100, post-filters only. Additional S3 costs not included in estimate."},
				"vectorCount":                map[string]interface{}{"type": "integer", "description": "Total number of vectors"},
				"dimensionsCount":            map[string]interface{}{"type": "integer", "description": "Vector dimensions (default: 384)"},
				"vectorMemoryCircuitBreaker": map[string]interface{}{"type": "integer", "description": "Memory circuit breaker percentage (default: 75)"},
				"maxEdges":                   map[string]interface{}{"type": "integer", "description": "HNSW max edges (default: 16)"},
				"segments":                   map[string]interface{}{"type": "integer", "description": "Segment count (default: 100)"},
				"nlist":                      map[string]interface{}{"type": "integer", "description": "IVF cluster count"},
				"PQEdges":                    map[string]interface{}{"type": "integer", "description": "PQ edges for HNSWPQ"},
				"subVectors":                 map[string]interface{}{"type": "integer", "description": "Sub-vectors for IVFPQ"},
				"codeSize":                   map[string]interface{}{"type": "integer", "description": "Code size for PQ methods"},
				"excludeVectorSource":        map[string]interface{}{"type": "boolean", "description": "Exclude vector source from storage calculation"},
				"exactKNN":                   map[string]interface{}{"type": "boolean", "description": "Use exact KNN (best accuracy, higher memory)"},
				"onDisk":                     map[string]interface{}{"type": "boolean", "description": "Enable on-disk mode with compression (lower memory, slightly higher latency)"},
				"compressionLevel":           map[string]interface{}{"type": "integer", "description": "On-disk compression: 2, 4, 8, 16, 32 (default: 32)"},
				"derivedSource":              map[string]interface{}{"type": "boolean", "description": "Enable derived source for ~30% storage reduction on non-vector data"},
				"zstdCompression":            map[string]interface{}{"type": "boolean", "description": "Enable ZSTD for ~20% storage reduction on non-vector data"},
				"preferInternalStorage":      map[string]interface{}{"type": "boolean", "description": "Prefer instance store over EBS (default: false)"},
				"storageClass":               map[string]interface{}{"type": "string", "description": "EBS type: gp3 (default) or NVME"},
				"freeStorageRequired":        map[string]interface{}{"type": "integer", "description": "Free storage percentage (default: 25)"},
				"indexExpansionRate":         map[string]interface{}{"type": "integer", "description": "Index expansion rate percentage (default: 25)"},
				"minimumJVM":                 map[string]interface{}{"type": "number", "description": "Minimum JVM heap in GB (default: 0)"},
				"dedicatedManager":           map[string]interface{}{"type": "boolean", "description": "Use dedicated manager nodes (default: true)"},
				"activePrimaryShards":        map[string]interface{}{"type": "integer", "description": "Override primary shards (default: 0, auto)"},
				"instanceTypes":              map[string]interface{}{"type": "array", "items": map[string]interface{}{"type": "string"}, "description": "Filter to specific instance types"},
				"instanceFamily":             map[string]interface{}{"type": "array", "items": map[string]interface{}{"type": "string"}, "description": "Filter: Memory optimized, Compute optimized, Storage optimized, OpenSearch optimized"},
				"edp":                        map[string]interface{}{"type": "number", "description": "Enterprise discount percentage"},
				"config":                     map[string]interface{}{"type": "string", "description": "Preset: dev or production"},
				"multiAzWithStandby":         map[string]interface{}{"type": "boolean", "description": "Multi-AZ with Standby (requires replicas>=2)"},
				"warmPercentage":             map[string]interface{}{"type": "integer", "description": "Percentage of vectors in warm tier (0-100, default: 0)"},
				"coldPercentage":             map[string]interface{}{"type": "integer", "description": "Percentage of vectors in cold/S3 (0-100, default: 0)"},
				"warmInstanceType":           map[string]interface{}{"type": "string", "description": "Override UltraWarm instance type"},
				"autoSelectWarmInstance":     map[string]interface{}{"type": "boolean", "description": "Auto-select warm instance (default: true)"},
			},
		},
		"timeSeries": map[string]interface{}{
			"type":        "object",
			"description": "Time-series workload estimation",
			"properties": map[string]interface{}{
				"size":                   map[string]interface{}{"type": "number", "description": "Data ingestion size in GB per period (hourly, daily, etc.)"},
				"targetShardSize":        map[string]interface{}{"type": "integer", "description": "Target shard size in GB (default: 45)"},
				"azs":                    map[string]interface{}{"type": "integer", "description": "Availability zones (default: 3)"},
				"replicas":               map[string]interface{}{"type": "integer", "description": "Replica count (default: 1)"},
				"CPUsPerShard":           map[string]interface{}{"type": "number", "description": "CPU cores per shard (default: 1.25)"},
				"hotRetentionPeriod":     map[string]interface{}{"type": "integer", "description": "Days in hot storage"},
				"warmRetentionPeriod":    map[string]interface{}{"type": "integer", "description": "Days in warm storage"},
				"coldRetentionPeriod":    map[string]interface{}{"type": "integer", "description": "Days in cold/S3 storage"},
				"region":                 map[string]interface{}{"type": "string", "description": "AWS region code (default: us-east-1)"},
				"pricingType":            map[string]interface{}{"type": "string", "description": "OnDemand, AURI1, AURI3, NURI1, NURI3, PURI1, PURI3"},
				"preferInternalStorage":  map[string]interface{}{"type": "boolean", "description": "Prefer instance store over EBS (default: false)"},
				"storageClass":           map[string]interface{}{"type": "string", "description": "EBS type: gp3 (default) or NVME"},
				"freeStorageRequired":    map[string]interface{}{"type": "integer", "description": "Free storage percentage (default: 25)"},
				"indexExpansionRate":     map[string]interface{}{"type": "integer", "description": "Index expansion rate percentage (default: 10). Negative values for strong compression."},
				"derivedSource":          map[string]interface{}{"type": "boolean", "description": "Enable derived source for ~30% storage reduction"},
				"zstdCompression":        map[string]interface{}{"type": "boolean", "description": "Enable ZSTD for ~20% reduction. Combine with derivedSource for ~44%."},
				"minimumJVM":             map[string]interface{}{"type": "number", "description": "Minimum JVM heap in GB (default: 0)"},
				"dedicatedManager":       map[string]interface{}{"type": "boolean", "description": "Use dedicated manager nodes (default: true)"},
				"activePrimaryShards":    map[string]interface{}{"type": "integer", "description": "Override primary shards (default: 0, auto)"},
				"instanceTypes":          map[string]interface{}{"type": "array", "items": map[string]interface{}{"type": "string"}, "description": "Filter to specific instance types"},
				"instanceFamily":         map[string]interface{}{"type": "array", "items": map[string]interface{}{"type": "string"}, "description": "Filter: Memory optimized, Compute optimized, Storage optimized, OpenSearch optimized"},
				"edp":                    map[string]interface{}{"type": "number", "description": "Enterprise discount percentage"},
				"config":                 map[string]interface{}{"type": "string", "description": "Preset: dev or production"},
				"multiAzWithStandby":     map[string]interface{}{"type": "boolean", "description": "Multi-AZ with Standby (requires replicas>=2)"},
				"warmInstanceType":       map[string]interface{}{"type": "string", "description": "Override warm instance type"},
				"autoSelectWarmInstance": map[string]interface{}{"type": "boolean", "description": "Auto-select warm instance (default: true)"},
				"remoteStorage": map[string]interface{}{
					"type":        "object",
					"description": "Remote storage for inactive shards",
					"properties": map[string]interface{}{
						"type": map[string]interface{}{"type": "string", "description": "DROP_REPLICA_FOR_NONACTIVE_SHARDS"},
					},
				},
			},
		},
	},
}

// ServerlessRequestSchema is the hand-crafted JSON schema for the serverless_v2_estimate tool.
// Kept from existing schema.go with slimmed descriptions.
var ServerlessRequestSchema = map[string]interface{}{
	"type":     "object",
	"required": []string{"ingest", "region"},
	"properties": map[string]interface{}{
		"maxConfigurations": map[string]interface{}{"type": "integer", "description": "Not used for serverless. Included for API consistency."},
		"region":            map[string]interface{}{"type": "string", "description": "AWS region (commercial only, serverless not available in China/isolated)"},
		"edp":               map[string]interface{}{"type": "number", "description": "Enterprise discount percentage"},
		"redundancy":        map[string]interface{}{"type": "boolean", "description": "Multi-AZ redundancy (default: true). Set false for dev."},
		"ingest": map[string]interface{}{
			"type":        "object",
			"description": "Ingestion workload (required)",
			"properties": map[string]interface{}{
				"minIndexingRate": map[string]interface{}{"type": "number", "description": "Min indexing rate in GB/hour"},
				"maxIndexingRate": map[string]interface{}{"type": "number", "description": "Max indexing rate in GB/hour"},
				"timePerDayAtMax": map[string]interface{}{"type": "number", "description": "Hours/day at max rate"},
				"minOCU":          map[string]interface{}{"type": "number", "description": "Min ingestion OCU"},
				"maxOCU":          map[string]interface{}{"type": "number", "description": "Max ingestion OCU"},
				"OCUHoursADay":    map[string]interface{}{"type": "number", "description": "OCU hours per day"},
				"replicas":        map[string]interface{}{"type": "integer", "description": "Replica count"},
			},
		},
		"search": map[string]interface{}{
			"type":        "object",
			"description": "Search workload (optional)",
			"properties": map[string]interface{}{
				"collectionSize":  map[string]interface{}{"type": "number", "description": "Collection size in GB"},
				"replicas":        map[string]interface{}{"type": "integer", "description": "Replica count"},
				"minQueryRate":    map[string]interface{}{"type": "integer", "description": "Min queries/hour"},
				"maxQueryRate":    map[string]interface{}{"type": "integer", "description": "Max queries/hour"},
				"hoursAtMaxRate":  map[string]interface{}{"type": "integer", "description": "Hours/day at max rate"},
				"derivedSource":   map[string]interface{}{"type": "boolean", "description": "~25% storage reduction"},
				"zstdCompression": map[string]interface{}{"type": "boolean", "description": "~15% storage reduction"},
				"minOCU":          map[string]interface{}{"type": "integer", "description": "Min search OCU"},
				"maxOCU":          map[string]interface{}{"type": "integer", "description": "Max search OCU"},
				"OCUHoursADay":    map[string]interface{}{"type": "integer", "description": "OCU hours per day"},
			},
		},
		"timeSeries": map[string]interface{}{
			"type":        "object",
			"description": "Time-series workload (optional)",
			"properties": map[string]interface{}{
				"dailyIndexSize":  map[string]interface{}{"type": "number", "description": "Daily index size in GB"},
				"replicas":        map[string]interface{}{"type": "integer", "description": "Replica count"},
				"daysInHot":       map[string]interface{}{"type": "integer", "description": "Days in hot storage"},
				"daysInWarm":      map[string]interface{}{"type": "integer", "description": "Days in warm storage"},
				"minQueryRate":    map[string]interface{}{"type": "integer", "description": "Min queries/hour"},
				"maxQueryRate":    map[string]interface{}{"type": "integer", "description": "Max queries/hour"},
				"hoursAtMaxRate":  map[string]interface{}{"type": "integer", "description": "Hours/day at max rate"},
				"derivedSource":   map[string]interface{}{"type": "boolean", "description": "~30% storage reduction"},
				"zstdCompression": map[string]interface{}{"type": "boolean", "description": "~20% storage reduction"},
				"minOCU":          map[string]interface{}{"type": "integer", "description": "Min OCU"},
				"maxOCU":          map[string]interface{}{"type": "integer", "description": "Max OCU"},
				"OCUHoursADay":    map[string]interface{}{"type": "integer", "description": "OCU hours per day"},
				"hotIndexSize":    map[string]interface{}{"type": "number", "description": "Hot index size in GB"},
				"warmIndexSize":   map[string]interface{}{"type": "number", "description": "Warm index size in GB"},
			},
		},
		"vector": map[string]interface{}{
			"type":        "object",
			"description": "Vector search workload (optional)",
			"properties": map[string]interface{}{
				"size":                       map[string]interface{}{"type": "number", "description": "Vector data size in GB"},
				"replicas":                   map[string]interface{}{"type": "integer", "description": "Replica count"},
				"vectorEngineType":           map[string]interface{}{"type": "string", "description": "hnsw, hnswfp16, hnswbv, ivf, ivfpq, hnswpq"},
				"vectorCount":                map[string]interface{}{"type": "integer", "description": "Total vector count"},
				"dimensionsCount":            map[string]interface{}{"type": "integer", "description": "Vector dimensions"},
				"vectorMemoryCircuitBreaker": map[string]interface{}{"type": "integer", "description": "Memory circuit breaker %"},
				"maxEdges":                   map[string]interface{}{"type": "integer", "description": "HNSW max edges"},
				"segments":                   map[string]interface{}{"type": "integer", "description": "Segment count"},
				"nlist":                      map[string]interface{}{"type": "integer", "description": "IVF cluster count"},
				"subVectors":                 map[string]interface{}{"type": "integer", "description": "IVFPQ sub-vectors"},
				"codeSize":                   map[string]interface{}{"type": "integer", "description": "PQ code size"},
				"PQEdges":                    map[string]interface{}{"type": "integer", "description": "PQ edges"},
				"onDisk":                     map[string]interface{}{"type": "boolean", "description": "On-disk mode with compression"},
				"compressionLevel":           map[string]interface{}{"type": "integer", "description": "On-disk compression: 2, 4, 8, 16, 32"},
				"derivedSource":              map[string]interface{}{"type": "boolean", "description": "~30% storage reduction on non-vector data"},
				"zstdCompression":            map[string]interface{}{"type": "boolean", "description": "~20% storage reduction on non-vector data"},
				"collectionSize":             map[string]interface{}{"type": "number", "description": "Collection size (calculated)"},
				"config":                     map[string]interface{}{"type": "string", "description": "Preset: dev or production"},
			},
		},
	},
}
