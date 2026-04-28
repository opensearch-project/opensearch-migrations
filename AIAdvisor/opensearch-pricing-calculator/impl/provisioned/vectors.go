// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"errors"
	"fmt"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/cache"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/commons"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
	"math"
	"sort"
	"strconv"
	"strings"

	"go.uber.org/zap"
)

type VectorEstimateRequest struct {
	DataSize                   float64  `json:"size"`
	TargetShardSize            int      `json:"targetShardSize"`
	Azs                        int      `json:"azs"`
	Replicas                   int      `json:"replicas"`
	CPUsPerShard               float32  `json:"CPUsPerShard"`
	PreferInternalStorage      *bool    `json:"preferInternalStorage,omitempty"`
	DedicatedManager           bool     `json:"dedicatedManager,omitempty"`
	StorageClass               string   `json:"storageClass"`
	FreeStorageRequired        int      `json:"freeStorageRequired"`
	ExpansionRate              int      `json:"indexExpansionRate"`
	Region                     string   `json:"region"`
	MinimumJVM                 float64  `json:"minimumJVM"`
	ActivePrimaryShards        int      `json:"activePrimaryShards"`
	PricingType                string   `json:"pricingType"`
	VectorMemoryCircuitBreaker int      `json:"vectorMemoryCircuitBreaker"`
	InstanceTypes              []string `json:"instanceTypes"`

	//vector related
	VectorEngineType    string `json:"vectorEngineType,omitempty"`
	VectorCount         int    `json:"vectorCount,omitempty"`
	DimensionsCount     int    `json:"dimensionsCount,omitempty"`
	MaxEdges            int    `json:"maxEdges,omitempty"`
	PQEdges             int    `json:"PQEdges"`
	SubVectors          int    `json:"subVectors,omitempty"`
	NList               int    `json:"nlist,omitempty"`
	CodeSize            int    `json:"codeSize,omitempty"`
	Segments            int    `json:"segments"`
	ExcludeVectorSource bool   `json:"excludeVectorSource"`
	ExactKNN            bool   `json:"exactKNN,omitempty"` // When true, vector memory is 0 but storage remains same

	// On-disk mode parameters
	OnDisk           bool `json:"onDisk,omitempty"`           // When true, applies compression to memory calculation (default: false)
	CompressionLevel int  `json:"compressionLevel,omitempty"` // Compression level for on-disk mode: 2, 4, 8, 16, 32 (default: 32)

	// Storage compression options (applies to non-vector data storage)
	DerivedSource   bool `json:"derivedSource,omitempty"`   // Enable derived source (~30% storage reduction for non-vector data)
	ZstdCompression bool `json:"zstdCompression,omitempty"` // Enable ZSTD compression (~20% storage reduction for non-vector data)

	Edp    float64 `json:"edp"`
	Config string  `json:"config"`

	// Multi-AZ with Standby option - when enabled, 1/3 of instances are standby and don't take traffic
	// Thread calculations use only 2/3 of instance count, and minimum 2 replicas are enforced
	MultiAzWithStandby bool `json:"multiAzWithStandby,omitempty"`

	// Warm and Cold storage tier support (percentage-based)
	// Allows distributing vectors across hot, warm (UltraWarm), and cold (S3) storage tiers
	WarmPercentage         int    `json:"warmPercentage,omitempty"`         // Percentage of vectors to store in warm tier (0-100)
	ColdPercentage         int    `json:"coldPercentage,omitempty"`         // Percentage of vectors to store in cold tier (0-100)
	WarmInstanceType       string `json:"warmInstanceType,omitempty"`       // Override UltraWarm instance type: "ultrawarm1.medium.search" or "ultrawarm1.large.search"
	AutoSelectWarmInstance *bool  `json:"autoSelectWarmInstance,omitempty"` // Enable automatic warm instance selection based on storage (default: true)
	DynamicSizing          bool   `json:"dynamicSizing,omitempty"`          // Enable workload-aware configuration scoring instead of cheapest-first ranking

	logger *zap.Logger
}

// GetDefaultVectorRequest returns a VectorEstimateRequest with default values.
//
// It creates a VectorEstimateRequest with default values for the fields:
// - TargetShardSize: 25
// - Azs: 1
// - Replicas: 1
// - FreeStorageRequired: 25
// - DedicatedManager: true
// - ExpansionRate: 10
// - CPUsPerShard: 1.5
// - Region: "US East (N. Virginia)"
// - MinimumJVM: 0
// - VectorMemoryCircuitBreaker: 75
// - Edp: 0.0
// - StorageClass: "gp3"
// - VectorEngineType: "hnsw"
// - DimensionsCount: 384
// - MaxEdges: 16
// - ExactKNN: false
// - OnDisk: false
// - CompressionLevel: 32
// - WarmPercentage: 0 (all vectors in hot tier by default)
// - ColdPercentage: 0 (no cold tier by default)
// - AutoSelectWarmInstance: nil (treated as true when nil)
//
// The function returns a pointer to a VectorEstimateRequest.
func GetDefaultVectorRequest() *VectorEstimateRequest {
	return &VectorEstimateRequest{
		TargetShardSize:            25,
		Azs:                        1,
		Replicas:                   1,
		FreeStorageRequired:        25,
		DedicatedManager:           true,
		ExpansionRate:              10,
		CPUsPerShard:               1.5,
		Region:                     "US East (N. Virginia)",
		MinimumJVM:                 0,
		VectorMemoryCircuitBreaker: 75,
		Edp:                        0.0,
		StorageClass:               "gp3",
		VectorEngineType:           "hnsw",
		DimensionsCount:            384,
		MaxEdges:                   16,
		ExactKNN:                   false, // Default to approximate KNN
		OnDisk:                     false, // Default to in-memory mode
		CompressionLevel:           32,    // Default compression level
		WarmPercentage:             0,     // All vectors in hot tier by default
		ColdPercentage:             0,     // No cold tier by default
		// AutoSelectWarmInstance is nil, which means auto-select is enabled by default
	}
}

// getDataScale returns the data scale tier based on vector count.
func (r *VectorEstimateRequest) getDataScale() string {
	switch {
	case r.VectorCount < 1_000_000:
		return "small"
	case r.VectorCount < 10_000_000:
		return "medium"
	case r.VectorCount < 100_000_000:
		return "large"
	default:
		return "xlarge"
	}
}

// Calculate calculates the EstimationResponse for a given VectorEstimateRequest.
//
// It calculates the required shards and memory for the vector collection and the required shards and memory for the ingest pipeline.
// It also calculates the price for the required resources.
//
// The function returns the calculated EstimationResponse.
func (r *VectorEstimateRequest) Calculate() (response EstimateResponse, err error) {
	if r.logger != nil {
		r.logger.Info("Starting vector estimate calculation",
			zap.Float64("dataSize", r.DataSize),
			zap.Int("targetShardSize", r.TargetShardSize),
			zap.Int("azs", r.Azs),
			zap.Int("replicas", r.Replicas),
			zap.String("region", r.Region),
			zap.String("vectorEngineType", r.VectorEngineType),
			zap.Int("vectorCount", r.VectorCount),
			zap.Int("dimensionsCount", r.DimensionsCount),
			zap.Int("warmPercentage", r.WarmPercentage),
			zap.Int("coldPercentage", r.ColdPercentage))
	}

	response.VectorRequest = r

	// Split vectors across tiers based on percentages
	hotVectorCount, warmVectorCount, coldVectorCount := r.getVectorCountForTier()

	if r.logger != nil {
		r.logger.Debug("Vector tier distribution",
			zap.Int("hotVectors", hotVectorCount),
			zap.Int("warmVectors", warmVectorCount),
			zap.Int("coldVectors", coldVectorCount))
	}

	// before calculating active primary shards, add the vector count to the data size, and index expansion rate
	withExpansion := r.DataSize * (1 + float64(r.ExpansionRate)/100)

	// Apply compression to non-vector data storage if enabled
	// Note: Vector data has its own compression (on-disk mode), so we use TimeSeries ratios for the non-vector data
	compressionMultiplier := commons.GetCompressionMultiplier(r.DerivedSource, r.ZstdCompression, commons.TimeSeriesCompressionRatios)
	withExpansion *= compressionMultiplier

	// Calculate required memory for HOT tier vectors only
	// Warm/cold tiers store vectors on disk/S3 - no memory required for graph traversal
	originalVectorCount := r.VectorCount
	r.VectorCount = hotVectorCount // Temporarily set for memory calculation
	response.TotalMemoryRequiredForVectors, response.TotalMemoryRequiredForVectorsCalc, err = r.GetRequiredMemory()
	r.VectorCount = originalVectorCount // Restore original count
	if err != nil {
		if r.logger != nil {
			r.logger.Error("Failed to calculate required memory for vectors",
				zap.Error(err),
				zap.String("vectorEngineType", r.VectorEngineType))
		}
		return response, err
	}

	// Calculate hot storage for HOT tier vectors only
	// S3 engine: vectors are fully offloaded to S3 (no _source, no graph, no vector memory on data nodes)
	var hotVectorSizeInGB float64
	if strings.ToLower(r.VectorEngineType) != "s3" {
		hotVectorSizeInGB = float64(r.DimensionsCount*4.0*hotVectorCount*(1+r.Replicas)) / (1024 * 1024 * 1024)
	}
	vectorStorage := response.TotalMemoryRequiredForVectors
	if !r.ExcludeVectorSource {
		vectorStorage += hotVectorSizeInGB
	}
	storageRequiredForData := withExpansion + (vectorStorage / float64(1+r.Replicas))

	// Calculate warm/cold storage
	response.TotalWarmStorage = r.calculateWarmVectorStorage(warmVectorCount)
	response.TotalColdStorage = r.calculateColdVectorStorage(coldVectorCount)

	// Add calculation strings for warm/cold storage
	// Note: Replicas are NOT applied - warm/cold storage tiers handle replication internally
	if response.TotalWarmStorage > 0 {
		response.TotalWarmStorageCalc = fmt.Sprintf("warmVectorCount:%d * dimensions:%d * 4bytes * (1 + expansionRate/200)",
			warmVectorCount, r.DimensionsCount)
	}
	if response.TotalColdStorage > 0 {
		response.TotalColdStorageCalc = fmt.Sprintf("coldVectorCount:%d * dimensions:%d * 4bytes",
			coldVectorCount, r.DimensionsCount)
	}

	// if active primary shards is not specified or zero, calculate it in response
	if r.ActivePrimaryShards == 0 {
		response.ActivePrimaryShards = calculateActivePrimaryShards(storageRequiredForData, r.TargetShardSize, r.Azs)
		if r.logger != nil {
			r.logger.Info("Calculated active primary shards",
				zap.Int("activePrimaryShards", response.ActivePrimaryShards),
				zap.Float64("dataSize", r.DataSize),
				zap.Int("targetShardSize", r.TargetShardSize),
				zap.String("vectorEngineType", r.VectorEngineType),
				zap.Int("vectorCount", r.VectorCount),
				zap.Int("dimensionsCount", r.DimensionsCount),
				zap.String("usecase", "Vector"),
				zap.Int("azs", r.Azs))
		}
	} else {
		response.ActivePrimaryShards = r.ActivePrimaryShards
		if r.logger != nil {
			r.logger.Debug("Using provided active primary shards",
				zap.Int("activePrimaryShards", response.ActivePrimaryShards))
		}
	}

	if r.logger != nil {
		r.logger.Debug("Calculated memory required for vectors",
			zap.Float64("totalMemoryRequiredForVectors", response.TotalMemoryRequiredForVectors),
			zap.String("vectorEngineType", r.VectorEngineType))
	}

	response.TotalActiveShards = response.ActivePrimaryShards * (1 + r.Replicas)
	FreeStorageMultiplier := 1 + float64(r.FreeStorageRequired)/100.0

	// Calculate TotalHotStorage with explicit formula
	response.TotalHotStorageCalc = "math.Round(datasize:" +
		formatFloat(r.DataSize) + "*(1+(FreeStorage:" +
		formatInt(r.FreeStorageRequired) + "+expansionRate:" +
		formatInt(r.ExpansionRate) + ")/100)*(1+(replica:" +
		formatInt(r.Replicas) + "))) + vectorStorage:" +
		formatFloat(vectorStorage)

	response.TotalHotStorage = math.Round(storageRequiredForData*FreeStorageMultiplier*(1+float64(r.Replicas))*100) / 100

	if r.logger != nil {
		r.logger.Debug("Calculated storage requirements",
			zap.Int("totalActiveShards", response.TotalActiveShards),
			zap.Float64("vectorStorage", vectorStorage),
			zap.Float64("totalHotStorage", response.TotalHotStorage),
			zap.Float64("totalWarmStorage", response.TotalWarmStorage),
			zap.Float64("totalColdStorage", response.TotalColdStorage),
			zap.Float64("FreeStorageMultiplier", FreeStorageMultiplier))
	}

	response.ClusterConfigs = r.GetClusterConfigs(response.TotalActiveShards, response.TotalHotStorage, response.TotalMemoryRequiredForVectors, response.TotalWarmStorage, response.TotalColdStorage)

	if r.logger != nil {
		r.logger.Info("Completed vector estimate calculation",
			zap.Int("totalActiveShards", response.TotalActiveShards),
			zap.Float64("totalHotStorage", response.TotalHotStorage),
			zap.Float64("totalMemoryRequiredForVectors", response.TotalMemoryRequiredForVectors),
			zap.Int("clusterConfigCount", len(response.ClusterConfigs)))
	}

	return
}

// GetClusterConfigs generates a list of cluster configurations based on the given totalActiveShards,
// totalStorage, totalVectorMemoryRequired, and warm/cold storage. It filters out instances that don't have enough JVM heap,
// or don't have the preferred storage type. It also filters out old generation instances and burstable
// instances. It then calculates the required node count for each instance type, and creates a ClusterConfig
// for each instance type.
//
// For warm/cold storage tiers:
// - UltraWarm (ultrawarm1.medium/large) can be used with any hot node type
// - When auto-selecting warm instance type, configs are generated for both medium and large to compare prices
//
// The ClusterConfig is then sorted by TotalCost, and only the top 10 are returned.
// If there are less than 10 ClusterConfigs, all of them are returned.
func (r *VectorEstimateRequest) GetClusterConfigs(totalActiveShards int, totalStorage float64, totalVectorMemoryRequired float64, totalWarmStorage float64, totalColdStorage float64) (cc []ClusterConfig) {
	requiredCPUs := int(math.Ceil(float64(totalActiveShards) * float64(r.CPUsPerShard)))
	provisionedRegion, err := cache.GetRegionProvisionedPrice(r.Region)
	if err == nil {
		nodes := provisionedRegion.HotInstances

		// Calculate cold storage once (same for all configurations)
		var coldStorage *ColdStorage
		if totalColdStorage > 0 {
			coldStorage = &ColdStorage{
				StorageRequired:    totalColdStorage,
				ManagedStorageCost: totalColdStorage * provisionedRegion.GetStorageUnitPrice("managedStorage"),
			}
		}

		// Determine which warm instance types to evaluate
		// - If user specified a type and disabled auto-select: only that type
		// - If auto-select enabled: both medium and large
		var warmInstanceTypesToEvaluate []string
		if totalWarmStorage > 0 {
			if r.WarmInstanceType != "" && !r.isAutoSelectWarmInstance() {
				// User specified a specific warm instance type
				warmInstanceTypesToEvaluate = []string{r.WarmInstanceType}
			} else {
				// Auto-select: evaluate both warm instance types to find best price
				warmInstanceTypesToEvaluate = AllWarmInstanceTypes
			}
		}

		for instanceType, instanceUnit := range nodes {
			//only process instances that have one of the instanceFamilies in the request
			if !r.IsInstanceTypesAllowed(instanceType) ||
				!instanceUnit.HasPricingOption(r.PricingType) ||
				// ignore old generation instances and burstable instances.
				oldGenPattern.MatchString(instanceType) ||
				// if impl storage is preferred, only use instances with impl storage
				r.PreferInternalStorage != nil && *r.PreferInternalStorage && !instanceUnit.Storage.HasInstanceStore() ||
				// if instance has not enough jvm heap, skip
				instanceUnit.JVMMemory < r.MinimumJVM {
				continue
			}

			if nodeCount, storagePerNode := instanceUnit.GetRequiredNodeCount(requiredCPUs, int(totalStorage), r.Azs, r.StorageClass, r.PreferInternalStorage, totalVectorMemoryRequired, r.VectorMemoryCircuitBreaker); nodeCount > 0 {
				nodeCount = int(math.Max(float64(nodeCount), float64(1+r.Replicas)))
				hotNodes := &HotNodes{
					Count:                 nodeCount,
					RequiredCPUs:          requiredCPUs,
					EstimatedActiveShards: totalActiveShards,
					AvailableCPUs:         nodeCount * instanceUnit.CPU,
					Type:                  instanceType,
					Family:                instanceUnit.Family,
					JVMMemoryPerNode:      instanceUnit.JVMMemory,
					JVMMemory:             float64(nodeCount) * instanceUnit.JVMMemory,
					Memory:                float64(nodeCount) * instanceUnit.Memory,
					VectorMemoryPerNode:   instanceUnit.GetVectorMemory(r.VectorMemoryCircuitBreaker),
					VectorMemory:          float64(nodeCount) * instanceUnit.GetVectorMemory(r.VectorMemoryCircuitBreaker),
					StorageRequired:       storagePerNode,
					HasInternalStorage:    instanceUnit.Storage.HasInstanceStore(),
				}
				if hotNodes.HasRemoteStorage() {
					hotNodes.RemoteStorageRequired = float64(storagePerNode / (1 + r.FreeStorageRequired/100.0))
				}
				hotNodes.CalculateMetricsWithStandby(r.MultiAzWithStandby)
				hotNodes.CalculatePrice(instanceUnit, provisionedRegion.GetStorageUnitPrice(r.StorageClass), provisionedRegion.GetStorageUnitPrice("managedStorage"))

				if totalWarmStorage > 0 {
					// Generate configs for each warm instance type to compare prices
					for _, warmInstanceType := range warmInstanceTypesToEvaluate {
						// OI2 warm instances can only be used with OpenSearch Optimized hot nodes
						if isOI2WarmInstance(warmInstanceType) && !isOpenSearchOptimizedInstance(instanceType) {
							continue
						}

						warmNodes, warmNodeErr := r.selectWarmInstanceForVectorsWithType(totalWarmStorage, warmInstanceType, nodes)
						if warmNodeErr != nil {
							continue
						}
						if warmNodes != nil {
							// Get warm instance pricing
							// OI2 instances use hot instance pricing, UltraWarm uses warm instance pricing
							var warmInstanceUnit price.InstanceUnit
							if isOI2WarmInstance(warmNodes.Type) {
								// OI2 warm nodes use hot instance pricing
								if iu, found := nodes[warmNodes.Type]; found {
									warmInstanceUnit = iu
								} else {
									continue // Skip if OI2 instance not found in hot instances
								}
							} else {
								// UltraWarm uses warm instance pricing
								warmInstanceUnitPtr, pricingErr := provisionedRegion.GetWarmNode(warmNodes.Type)
								if pricingErr != nil {
									continue
								}
								warmInstanceUnit = *warmInstanceUnitPtr
							}

							// Calculate CPUs and thread metrics from pricing API data
							warmNodes.AvailableCPUs = warmInstanceUnit.CPU * warmNodes.Count
							warmNodes.CalculateMetrics()

							warmNodes.CalculatePrice(warmInstanceUnit, provisionedRegion.GetStorageUnitPrice("managedStorage"), isOI2WarmInstance(warmNodes.Type))
						}
						newClusterConfig, err := r.GetClusterConfigFor(hotNodes, warmNodes, coldStorage)
						if err != nil {
							continue
						}
						cc = append(cc, newClusterConfig)
					}
				} else {
					// No warm storage requested: just create config without warm/cold
					newClusterConfig, err := r.GetClusterConfigFor(hotNodes, nil, nil)
					if err != nil {
						continue
					}
					cc = append(cc, newClusterConfig)
				}
			}
		}
	}
	// sort by TotalCost
	sort.Slice(cc, func(i, j int) bool {
		return cc[i].TotalCost < cc[j].TotalCost
	})

	maxConfigs := 10
	if r.DynamicSizing {
		// Keep 3x candidates for scoring, then rank and return top N
		oversampleLimit := maxConfigs * 3
		if len(cc) > oversampleLimit {
			cc = cc[:oversampleLimit]
		}
		scoringProfile := "vector"
		if strings.ToLower(r.VectorEngineType) == "s3" {
			scoringProfile = "s3"
		}
		cc = ScoreAndRank(cc, scoringProfile, r.getDataScale(), maxConfigs, totalVectorMemoryRequired)
	} else {
		if len(cc) > maxConfigs {
			cc = cc[:maxConfigs]
		}
	}
	return cc
}

// IsInstanceTypesAllowed checks if the given instance type is allowed based on the InstanceTypes filter
func (r *VectorEstimateRequest) IsInstanceTypesAllowed(instanceType string) bool {
	// If no instance types specified, allow all
	if len(r.InstanceTypes) == 0 {
		return true
	}

	// Check if the instance type matches any of the specified instance types
	for _, instanceTypeAllowed := range r.InstanceTypes {
		if strings.HasPrefix(instanceType, instanceTypeAllowed) {
			return true
		}
	}
	return false
}

// Normalize sets default values for VectorEstimateRequest fields if they are not specified. It defaults pricing type to on-demand,
// target shard size to 30 and segments to 100. It also normalizes region input for backward compatibility.
func (r *VectorEstimateRequest) Normalize() {
	// Normalize region input to canonical display name for cache lookup
	if r.Region != "" {
		r.Region = regions.NormalizeRegionInput(r.Region)
	}

	// if no pricing type is specified or not one of the values in PriceKeys array, default to on-demand
	if r.PricingType == "" || PriceOptionsMap[r.PricingType] == "" {
		r.PricingType = OnDemand
	}
	// if target shard size is not specified or zero, default to 30
	if r.TargetShardSize <= 0 {
		r.TargetShardSize = 30
	}
	if r.Segments <= 0 {
		r.Segments = 100
	}

	// Validate and normalize on-disk mode parameters
	r.validateOnDiskMode()

	// S3 vector engine constraints
	if strings.ToLower(r.VectorEngineType) == "s3" {
		// S3 requires all-hot: no warm/cold tiers
		r.WarmPercentage = 0
		r.ColdPercentage = 0
		// S3 has no in-memory graph: on-disk mode not applicable
		r.OnDisk = false
		// Filter instance types to OpenSearch Optimized only (OR1, OR2, OM2, OI2)
		if len(r.InstanceTypes) > 0 {
			var filtered []string
			for _, it := range r.InstanceTypes {
				if isOpenSearchOptimizedPrefix(it) {
					filtered = append(filtered, it)
				}
			}
			r.InstanceTypes = filtered
		}
		// Default to all OS-optimized prefixes if none specified or none survived filtering
		if len(r.InstanceTypes) == 0 {
			r.InstanceTypes = []string{"or1.", "or2.", "om2.", "oi2."}
		}
	}

	// Validate and normalize warm/cold tier percentages
	r.validateTierPercentages()

	r.HandleConfigGroups()

	// When multi-AZ with standby is enabled, enforce minimum 2 replicas
	if r.MultiAzWithStandby && r.Replicas < 2 {
		r.Replicas = 2
	}
}

// GetRequiredMemory returns the estimated memory required in GiB for the given vector count and vector engine type,
// and a string detailing the formula used for the calculation.
// The result is rounded up to the next whole GiB.
// The calculation is based on the formula given in the documentation for OpenSearch's KNN plugin, which is itself based on the paper "Hnswlib: A fast and efficient library for hierarchical navigable small world graphs".
// If the vector engine type is not supported, an error is returned.
// If ExactKNN is true, returns 0 memory as no vector index is maintained in memory.
func (r *VectorEstimateRequest) GetRequiredMemory() (reqMemory float64, calcString string, err error) {
	// If ExactKNN is enabled, no vector memory is required as searches are performed directly on storage
	if r.ExactKNN {
		calcString = "exactKNN:true,vectorMemory:0"
		return 0.0, calcString, nil
	}

	// Product quantization memory formula — see OpenSearch k-NN plugin documentation
	var reqMemoryInBytes float64

	// Initialize calcString with engine type
	calcString = "vectorEngine:" + r.VectorEngineType

	switch strings.ToLower(r.VectorEngineType) {
	case "nmslib", "hnsw":
		dimFactor := 4.0
		if r.OnDisk {
			dimFactor = dimFactor / float64(r.CompressionLevel)
			calcString += fmt.Sprintf(",onDisk:true,compression:%d", r.CompressionLevel)
		}
		reqMemoryInBytes = 1.1 * (dimFactor*float64(r.DimensionsCount) + 8.0*float64(r.MaxEdges)) * float64(r.VectorCount)
		calcString += ",formula:1.1*(" + formatFloat(dimFactor) + "*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "+8.0*maxEdges:" +
			strconv.Itoa(r.MaxEdges) + ")*vectorCount:" + strconv.Itoa(r.VectorCount)
	case "hnswfp16":
		reqMemoryInBytes = 1.1 * (2.0*float64(r.DimensionsCount) + 8.0*float64(r.MaxEdges)) * float64(r.VectorCount)
		calcString += ",formula:1.1*(2.0*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "+8.0*maxEdges:" +
			strconv.Itoa(r.MaxEdges) + ")*vectorCount:" + strconv.Itoa(r.VectorCount)
	case "hnswint8":
		reqMemoryInBytes = 1.1 * (1.0*float64(r.DimensionsCount) + 8.0*float64(r.MaxEdges)) * float64(r.VectorCount)
		calcString += ",formula:1.1*(1.0*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "+8.0*maxEdges:" +
			strconv.Itoa(r.MaxEdges) + ")*vectorCount:" + strconv.Itoa(r.VectorCount)
	case "hnswbv":
		reqMemoryInBytes = 1.1 * (float64(r.DimensionsCount)/8.0 + 8.0*float64(r.MaxEdges)) * float64(r.VectorCount)
		calcString += ",formula:1.1*(dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "/8.0+8.0*maxEdges:" +
			strconv.Itoa(r.MaxEdges) + ")*vectorCount:" + strconv.Itoa(r.VectorCount)
	case "ivf":
		dimFactor := 4.0
		if r.OnDisk {
			dimFactor = dimFactor / float64(r.CompressionLevel)
			calcString += fmt.Sprintf(",onDisk:true,compression:%d", r.CompressionLevel)
		}
		reqMemoryInBytes = 1.1 * (((dimFactor*float64(r.DimensionsCount) + 24.0) * float64(r.VectorCount)) + (dimFactor * float64(r.NList) * float64(r.DimensionsCount)))
		calcString += ",formula:1.1*(((" + formatFloat(dimFactor) + "*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "+24.0)*vectorCount:" +
			strconv.Itoa(r.VectorCount) + ")+(" + formatFloat(dimFactor) + "*nList:" + strconv.Itoa(r.NList) + "*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "))"
	case "ivffp16":
		reqMemoryInBytes = 1.1 * ((2.0 * float64(r.DimensionsCount) * float64(r.VectorCount)) + (4.0 * float64(r.NList) * float64(r.DimensionsCount)))
		calcString += ",formula:1.1*((2.0*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "*vectorCount:" +
			strconv.Itoa(r.VectorCount) + ")+(4.0*nList:" + strconv.Itoa(r.NList) + "*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "))"
	case "ivfint8":
		reqMemoryInBytes = 1.1 * ((1.0 * float64(r.DimensionsCount) * float64(r.VectorCount)) + (4.0 * float64(r.NList) * float64(r.DimensionsCount)))
		calcString += ",formula:1.1*((1.0*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "*vectorCount:" +
			strconv.Itoa(r.VectorCount) + ")+(4.0*nList:" + strconv.Itoa(r.NList) + "*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "))"
	case "ivfbv":
		reqMemoryInBytes = 1.1 * ((float64(r.DimensionsCount) / 8 * float64(r.VectorCount)) + (float64(r.NList) * float64(r.DimensionsCount)))
		calcString += ",formula:1.1*((dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "/8*vectorCount:" +
			strconv.Itoa(r.VectorCount) + ")+(nList:" + strconv.Itoa(r.NList) + "*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "))"
	case "ivfpq":
		comp1 := ((float64(r.CodeSize)/8.0)*float64(r.SubVectors) + 24.0) * float64(r.VectorCount)
		comp2 := 4.0 * float64(r.NList) * float64(r.DimensionsCount)
		comp3 := math.Pow(2, float64(r.CodeSize)) * 4.0 * float64(r.DimensionsCount)
		reqMemoryInBytes = 1.1 * (comp1 + comp2 + comp3)
		calcString += ",formula:1.1*(((codeSize:" + strconv.Itoa(r.CodeSize) + "/8.0)*subVectors:" +
			strconv.Itoa(r.SubVectors) + "+24.0)*vectorCount:" + strconv.Itoa(r.VectorCount) + "+" +
			"(4.0*nList:" + strconv.Itoa(r.NList) + "*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + ")+" +
			"(2^codeSize:" + strconv.Itoa(r.CodeSize) + "*4.0*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "))"
	case "hnswpq":
		comp1 := ((float64(r.CodeSize)/8.0)*float64(r.PQEdges) + 24.0 + 8*float64(r.MaxEdges)) * float64(r.VectorCount)
		comp2 := float64(r.Segments) * (math.Pow(2, float64(r.CodeSize)) * 4.0 * float64(r.DimensionsCount))
		reqMemoryInBytes = 1.1 * (comp1 + comp2)
		calcString += ",formula:1.1*(((codeSize:" + strconv.Itoa(r.CodeSize) + "/8.0)*pqEdges:" +
			strconv.Itoa(r.PQEdges) + "+24.0+8*maxEdges:" + strconv.Itoa(r.MaxEdges) + ")*vectorCount:" +
			strconv.Itoa(r.VectorCount) + "+segments:" + strconv.Itoa(r.Segments) + "*(2^codeSize:" +
			strconv.Itoa(r.CodeSize) + "*4.0*dimensionsCount:" + strconv.Itoa(r.DimensionsCount) + "))"
	case "exactknn":
		calcString += "ExactKNN needs no memory"
		return 0.0, calcString, nil
	case "s3":
		calcString += ",s3:true,vectorMemory:0"
		return 0.0, calcString, nil
	default:
		calcString += ",error:unsupported_engine_type"
		return 0.0, calcString, errors.New("engine type is not supported")
	}

	reqMemory = reqMemoryInBytes * float64(1+r.Replicas) / (1024 * 1024 * 1024)
	return
}

// GetClusterConfigFor takes a HotNodes, WarmNodes, and ColdStorage objects and returns a ClusterConfig object.
// If the `DedicatedManager` flag is set to true, it will calculate the leader nodes
// required for the given hot nodes and add the leader nodes cost to the total cost.
// Warm nodes cost includes UltraWarm instance cost and managed storage cost.
// Cold storage cost is managed storage cost for S3.
// If the `Edp` field is set, it will calculate the discount based on the total cost
// and the Edp percentage, and then calculate the discounted total cost.
func (r *VectorEstimateRequest) GetClusterConfigFor(nodes *HotNodes, warmNodes *WarmNodes, coldStorage *ColdStorage) (cc ClusterConfig, err error) {
	cc.HotNodes = nodes
	cc.TotalCost = cc.HotNodes.Price[r.PricingType].TotalCost

	// Add warm nodes to cluster config
	if warmNodes != nil {
		cc.WarmNodes = warmNodes
		// OI2 warm instances support all pricing types, UltraWarm only supports OnDemand
		warmPricingType := OnDemand
		if isOI2WarmInstance(warmNodes.Type) {
			warmPricingType = r.PricingType
		}
		cc.TotalCost += warmNodes.Price[warmPricingType].TotalCost + warmNodes.ManagedStorageCost
	}

	// Add cold storage to cluster config
	if coldStorage != nil {
		cc.ColdStorage = coldStorage
		cc.TotalCost += coldStorage.ManagedStorageCost
	}

	// Calculate leader nodes with hot nodes (and warm nodes if present) if dedicated manager is set to true
	if r.DedicatedManager {
		totalNodeCount := nodes.Count
		if warmNodes != nil {
			totalNodeCount += warmNodes.Count
		}
		cc.LeaderNodes, err = getLeaderNodesFor(nodes.Type, r.Region, totalNodeCount)
		if err != nil {
			return
		}
		cc.TotalCost += cc.LeaderNodes.Price[r.PricingType].TotalCost
	}

	if r.Edp != 0 {
		cc.Edp = r.Edp
		cc.Discount = cc.TotalCost * (r.Edp / 100)
		cc.DiscountedTotal = cc.TotalCost - cc.Discount
	}

	return

}

// isUncompressedEngine checks if the given vector engine type is uncompressed (HNSW or IVF)
// These are the only engines that support on-disk mode with compression
func isUncompressedEngine(engineType string) bool {
	engineLower := strings.ToLower(engineType)
	return engineLower == "hnsw" || engineLower == "nsmlib" || engineLower == "ivf"
}

// isValidCompressionLevel checks if the compression level is one of the allowed values
func isValidCompressionLevel(level int) bool {
	validLevels := []int{2, 4, 8, 16, 32}
	for _, valid := range validLevels {
		if level == valid {
			return true
		}
	}
	return false
}

// isOpenSearchOptimizedPrefix checks if the given prefix matches an OpenSearch Optimized instance family.
// It handles both full instance names (e.g., "or2.xlarge.search") and short prefixes (e.g., "or2", "oi2")
// by checking bidirectional prefix matching against the OS-optimized families (or1, or2, om2, oi2).
func isOpenSearchOptimizedPrefix(prefix string) bool {
	prefixLower := strings.ToLower(prefix)
	osPrefixes := []string{"or1", "or2", "om2", "oi2"}
	for _, osPrefix := range osPrefixes {
		if strings.HasPrefix(prefixLower, osPrefix) || strings.HasPrefix(osPrefix, prefixLower) {
			return true
		}
	}
	return false
}

// validateOnDiskMode validates and normalizes the on-disk mode parameters
func (r *VectorEstimateRequest) validateOnDiskMode() {
	// If OnDisk is false, ensure CompressionLevel is set to default (32) for consistency
	if !r.OnDisk {
		if r.CompressionLevel == 0 {
			r.CompressionLevel = 32
		}
		return
	}

	// OnDisk is true - validate engine type compatibility
	if !isUncompressedEngine(r.VectorEngineType) {
		// Reset OnDisk to false for incompatible engines
		r.OnDisk = false
		r.CompressionLevel = 32
		return
	}

	// Validate compression level
	if r.CompressionLevel == 0 {
		r.CompressionLevel = 32 // Set default
	} else if !isValidCompressionLevel(r.CompressionLevel) {
		// Reset to default for invalid compression levels
		r.CompressionLevel = 32
	}
}

// validateTierPercentages validates and normalizes the warm/cold tier percentages
// Ensures that warm + cold <= 100%, scales proportionally if exceeded, and validates warm instance type
func (r *VectorEstimateRequest) validateTierPercentages() {
	// Ensure percentages are within valid bounds
	if r.WarmPercentage < 0 {
		r.WarmPercentage = 0
	}
	if r.WarmPercentage > 100 {
		r.WarmPercentage = 100
	}
	if r.ColdPercentage < 0 {
		r.ColdPercentage = 0
	}
	if r.ColdPercentage > 100 {
		r.ColdPercentage = 100
	}

	// Ensure total doesn't exceed 100%
	total := r.WarmPercentage + r.ColdPercentage
	if total > 100 {
		// Scale down proportionally to fit within 100%
		factor := 100.0 / float64(total)
		r.WarmPercentage = int(float64(r.WarmPercentage) * factor)
		r.ColdPercentage = 100 - r.WarmPercentage
	}

	// Validate WarmInstanceType if specified
	// Valid types: UltraWarm (any hot nodes) and OI2 (OpenSearch Optimized hot nodes only)
	if r.WarmInstanceType != "" {
		validTypes := []string{
			"ultrawarm1.medium.search", "ultrawarm1.large.search",
			"oi2.xlarge.search", "oi2.2xlarge.search", "oi2.4xlarge.search",
			"oi2.8xlarge.search", "oi2.16xlarge.search",
		}
		isValid := false
		for _, valid := range validTypes {
			if r.WarmInstanceType == valid {
				isValid = true
				break
			}
		}
		if !isValid {
			// Reset to empty to allow auto-selection
			r.WarmInstanceType = ""
		}
	}
}

// isAutoSelectWarmInstance returns true if automatic warm instance selection is enabled
// When AutoSelectWarmInstance is nil, it defaults to true
func (r *VectorEstimateRequest) isAutoSelectWarmInstance() bool {
	return r.AutoSelectWarmInstance == nil || *r.AutoSelectWarmInstance
}

// getVectorCountForTier returns the number of vectors for each storage tier based on percentages
func (r *VectorEstimateRequest) getVectorCountForTier() (hotVectorCount, warmVectorCount, coldVectorCount int) {
	hotPercentage := 100 - r.WarmPercentage - r.ColdPercentage
	hotVectorCount = int(float64(r.VectorCount) * float64(hotPercentage) / 100)
	warmVectorCount = int(float64(r.VectorCount) * float64(r.WarmPercentage) / 100)
	coldVectorCount = r.VectorCount - hotVectorCount - warmVectorCount // Remaining to avoid rounding errors
	return
}

// calculateWarmVectorStorage calculates the storage required for vectors in warm tier (UltraWarm)
// Warm tier stores vectors on SSD-backed managed storage, with lower index overhead than hot tier
// Formula: dimensions * 4 bytes * warmVectorCount * (1 + expansionRate/200)
// Note: Replicas are NOT applied to warm storage - UltraWarm manages its own replication
// The expansion rate is halved because warm tier uses a simpler index structure
func (r *VectorEstimateRequest) calculateWarmVectorStorage(warmVectorCount int) float64 {
	if warmVectorCount == 0 {
		return 0
	}

	// Vector data size = dimensions * 4 bytes * vector count
	vectorSizeGB := float64(r.DimensionsCount*4*warmVectorCount) / (1024 * 1024 * 1024)

	// Apply reduced expansion rate (half of hot tier) for index overhead
	// Warm tier uses simpler flat index structure compared to hot tier's graph-based indices
	expansionMultiplier := 1 + float64(r.ExpansionRate)/200.0

	// Note: No replica multiplier for warm storage - UltraWarm handles replication internally
	return math.Round(vectorSizeGB*expansionMultiplier*100) / 100
}

// calculateColdVectorStorage calculates the storage required for vectors in cold tier (S3)
// Cold tier stores raw vectors without index structures - purely for archival
// Formula: dimensions * 4 bytes * coldVectorCount
// Note: Replicas are NOT applied to cold storage - S3 manages its own durability
func (r *VectorEstimateRequest) calculateColdVectorStorage(coldVectorCount int) float64 {
	if coldVectorCount == 0 {
		return 0
	}

	// Raw vector storage only (no index structure)
	vectorSizeGB := float64(r.DimensionsCount*4*coldVectorCount) / (1024 * 1024 * 1024)

	// Note: No replica multiplier for cold storage - S3 handles durability internally
	return math.Round(vectorSizeGB*100) / 100
}

// selectWarmInstanceForVectors selects the appropriate UltraWarm instance type and calculates node count
// Returns nil if warm percentage is 0 or no warm storage is needed
func (r *VectorEstimateRequest) selectWarmInstanceForVectors(totalWarmStorage float64) (*WarmNodes, error) {
	if r.WarmPercentage == 0 || totalWarmStorage == 0 {
		return nil, nil
	}

	// Determine instance type
	var instanceType string
	if r.WarmInstanceType != "" && !r.isAutoSelectWarmInstance() {
		// Use user-specified instance type
		instanceType = r.WarmInstanceType
	} else {
		// Auto-select based on storage size
		// Use medium for < 39TB, large for >= 39TB (threshold from time-series logic)
		if totalWarmStorage < 39*1024 {
			instanceType = "ultrawarm1.medium.search"
		} else {
			instanceType = "ultrawarm1.large.search"
		}
	}

	// Pass nil for hotInstances since auto-select only uses UltraWarm instances
	// UltraWarm cache sizes are constants, no API lookup needed
	return r.selectWarmInstanceForVectorsWithType(totalWarmStorage, instanceType, nil)
}

// selectWarmInstanceForVectorsWithType creates WarmNodes configuration for a specific warm instance type
// This is used when generating multiple configurations with different warm instance types for price comparison
// Supports both UltraWarm (ultrawarm1.medium/large) and OI2 instances as warm nodes
// hotInstances is used to look up OI2 storage sizes from pricing API
func (r *VectorEstimateRequest) selectWarmInstanceForVectorsWithType(totalWarmStorage float64, warmInstanceType string, hotInstances map[string]price.InstanceUnit) (*WarmNodes, error) {
	if totalWarmStorage == 0 {
		return nil, nil
	}

	warmNodes := &WarmNodes{
		Type: warmInstanceType,
	}

	// Calculate node count based on instance type
	// UltraWarm uses managed storage with cache
	// OI2 uses NVMe as cache with managed storage backend
	cacheSize := getWarmInstanceCacheSize(warmInstanceType, hotInstances)
	if cacheSize == 0 {
		return nil, fmt.Errorf("unknown warm instance type: %s", warmInstanceType)
	}

	// Calculate required nodes (minimum 2 for high availability)
	warmNodes.Count = int(math.Max(math.Ceil(totalWarmStorage/cacheSize), 2.0))

	// Apply AZ-based maximum node limits
	// UltraWarm: 250 per AZ
	// OI2: 1002 per AZ (same as hot instances)
	var maxNodes int
	if isOI2WarmInstance(warmInstanceType) {
		maxNodes = 1002 * r.Azs
	} else {
		maxNodes = 250 * r.Azs
	}

	if warmNodes.Count > maxNodes {
		return nil, fmt.Errorf("warm storage %.2f GB requires %d %s nodes, exceeding maximum %d for %d AZ(s)",
			totalWarmStorage, warmNodes.Count, warmInstanceType, maxNodes, r.Azs)
	}

	warmNodes.StorageRequired = int(totalWarmStorage)

	// Note: CPU and thread metrics are calculated in GetClusterConfigs
	// where we have access to pricing API data for the instance type

	return warmNodes, nil
}

// HandleConfigGroups will set the required fields on the VectorEstimateRequest
// based on a pre-defined config. The valid config values are "dev" and
// "production". The fields that are set are:
// - MinimumJVM
// - Azs
// - CPUsPerShard
// - Replicas
// - DedicatedManager
func (r *VectorEstimateRequest) HandleConfigGroups() {
	config := strings.ToLower(r.Config)
	switch config {
	case "dev":
		r.MinimumJVM = 2
		r.Azs = 1
		r.CPUsPerShard = 1.0
		r.Replicas = 0
		r.DedicatedManager = false
	case "production":
		r.MinimumJVM = 8
		r.Azs = 3
		r.Replicas = 1
		r.CPUsPerShard = 1.25
		r.DedicatedManager = true
	}

}
