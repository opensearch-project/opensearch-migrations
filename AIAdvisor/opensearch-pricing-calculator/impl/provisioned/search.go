// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"fmt"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/cache"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/commons"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
	"math"
	"sort"
	"strings"

	"go.uber.org/zap"
)

type SearchEstimateRequest struct {
	DataSize              float64 `json:"size"`
	TargetShardSize       int     `json:"targetShardSize"`
	Azs                   int     `json:"azs"`
	Replicas              int     `json:"replicas"`
	CPUsPerShard          float32 `json:"CPUsPerShard"`
	PreferInternalStorage *bool   `json:"preferInternalStorage,omitempty"`
	DedicatedManager      bool    `json:"dedicatedManager,omitempty"`
	StorageClass          string  `json:"storageClass"`
	FreeStorageRequired   int     `json:"freeStorageRequired"`
	ExpansionRate         int     `json:"indexExpansionRate"`
	// Storage compression options
	DerivedSource       bool     `json:"derivedSource,omitempty"`   // Enable derived source (~25% storage reduction)
	ZstdCompression     bool     `json:"zstdCompression,omitempty"` // Enable ZSTD compression (~15% storage reduction)
	Region              string   `json:"region"`
	MinimumJVM          float64  `json:"minimumJVM"`
	ActivePrimaryShards int      `json:"activePrimaryShards"`
	PricingType         string   `json:"pricingType"`
	Edp                 float64  `json:"edp"`
	Config              string   `json:"config"`
	InstanceTypes       []string `json:"instanceTypes"`
	// Multi-AZ with Standby option - when enabled, 1/3 of instances are standby and don't take traffic
	// Thread calculations use only 2/3 of instance count, and minimum 2 replicas are enforced
	MultiAzWithStandby bool `json:"multiAzWithStandby,omitempty"`

	// Warm and Cold storage tier support (percentage-based)
	// Allows distributing data across hot, warm (UltraWarm), and cold (S3) storage tiers
	WarmPercentage         int    `json:"warmPercentage,omitempty"`         // Percentage of data to store in warm tier (0-100)
	ColdPercentage         int    `json:"coldPercentage,omitempty"`         // Percentage of data to store in cold tier (0-100)
	WarmInstanceType       string `json:"warmInstanceType,omitempty"`       // Override UltraWarm instance type: "ultrawarm1.medium.search" or "ultrawarm1.large.search"
	AutoSelectWarmInstance *bool  `json:"autoSelectWarmInstance,omitempty"` // Enable automatic warm instance selection based on storage (default: true)
	DynamicSizing          bool   `json:"dynamicSizing,omitempty"`          // Enable workload-aware configuration scoring instead of cheapest-first ranking

	logger *zap.Logger
}

// GetDefaultSearchRequest returns a SearchEstimateRequest with default values.
//
// It creates a SearchEstimateRequest with default values for the fields:
// - TargetShardSize: 25
// - Azs: 1
// - Replicas: 1
// - FreeStorageRequired: 25
// - DedicatedManager: true
// - ExpansionRate: 10
// - CPUsPerShard: 1.5
// - Edp: 0.0
// - Region: "US East (N. Virginia)"
// - MinimumJVM: 0
// - StorageClass: "gp3"
// - WarmPercentage: 0 (all data in hot tier by default)
// - ColdPercentage: 0 (no cold tier by default)
// - AutoSelectWarmInstance: nil (treated as true when nil)
//
// The function returns a pointer to a SearchEstimateRequest.
func GetDefaultSearchRequest() *SearchEstimateRequest {
	return &SearchEstimateRequest{
		TargetShardSize:     25,
		Azs:                 1,
		Replicas:            1,
		FreeStorageRequired: 25,
		DedicatedManager:    true,
		ExpansionRate:       10,
		CPUsPerShard:        1.5,
		Edp:                 0.0,
		Region:              "US East (N. Virginia)",
		MinimumJVM:          0,
		StorageClass:        "gp3",
		WarmPercentage:      0, // All data in hot tier by default
		ColdPercentage:      0, // No cold tier by default
		// AutoSelectWarmInstance is nil, which means auto-select is enabled by default
	}
}

// getDataScale returns the data scale tier based on data size.
func (r *SearchEstimateRequest) getDataScale() string {
	switch {
	case r.DataSize < 100:
		return "small"
	case r.DataSize < 1000:
		return "medium"
	case r.DataSize < 10000:
		return "large"
	default:
		return "xlarge"
	}
}

// Calculate calculates the EstimationResponse for a given SearchEstimateRequest.
//
// It calculates the required shards and memory for the search collection and the required shards and memory for the ingest pipeline.
// It also calculates the price for the required resources.
// Supports warm/cold storage tiers for distributing data across hot, UltraWarm, and cold (S3) storage.
//
// The function returns the calculated EstimationResponse.
func (r *SearchEstimateRequest) Calculate() (response EstimateResponse, err error) {
	if r.logger != nil {
		r.logger.Info("Starting search estimate calculation",
			zap.Float64("dataSize", r.DataSize),
			zap.Int("targetShardSize", r.TargetShardSize),
			zap.String("usecase", "Search"),
			zap.Int("azs", r.Azs),
			zap.Int("replicas", r.Replicas),
			zap.String("region", r.Region),
			zap.Int("warmPercentage", r.WarmPercentage),
			zap.Int("coldPercentage", r.ColdPercentage))
	}

	response.SearchRequest = r

	// Split data size by tier percentages
	hotDataSize, warmDataSize, coldDataSize := r.getStorageForTier(r.DataSize)

	if r.logger != nil {
		r.logger.Debug("Data tier distribution",
			zap.Float64("hotDataSize", hotDataSize),
			zap.Float64("warmDataSize", warmDataSize),
			zap.Float64("coldDataSize", coldDataSize))
	}

	// if active primary shards is not specified or zero, calculate it in response (based on hot tier data only)
	if r.ActivePrimaryShards == 0 {
		response.ActivePrimaryShards = calculateActivePrimaryShards(hotDataSize*float64(1+r.ExpansionRate/100), r.TargetShardSize, r.Azs)
		if r.logger != nil {
			r.logger.Debug("Calculated active primary shards",
				zap.Int("activePrimaryShards", response.ActivePrimaryShards),
				zap.Float64("hotDataSize", hotDataSize),
				zap.Int("targetShardSize", r.TargetShardSize),
				zap.Int("azs", r.Azs))
		}
	} else {
		response.ActivePrimaryShards = r.ActivePrimaryShards
		if r.logger != nil {
			r.logger.Debug("Using provided active primary shards",
				zap.Int("activePrimaryShards", response.ActivePrimaryShards))
		}
	}

	response.TotalActiveShards = response.ActivePrimaryShards * (1 + r.Replicas)
	multiplier := 1 + float64(r.FreeStorageRequired+r.ExpansionRate)/100.0

	// Calculate compression multiplier if derived source or ZSTD compression is enabled
	compressionMultiplier := commons.GetCompressionMultiplier(r.DerivedSource, r.ZstdCompression, commons.SearchCompressionRatios)

	// Calculate TotalHotStorage with explicit formula (hot tier only)
	response.TotalHotStorageCalc = "math.Round(hotDataSize:" +
		formatFloat(hotDataSize) + "*(1+(FreeStorage:" +
		formatInt(r.FreeStorageRequired) + "+expansionRate:" +
		formatInt(r.ExpansionRate) + ")/100)*(1+(replica:" +
		formatInt(r.Replicas) + ")))"
	response.TotalHotStorage = math.Round(hotDataSize*multiplier*(1.0+float64(r.Replicas))*compressionMultiplier*100) / 100

	// Calculate warm/cold storage
	response.TotalWarmStorage = r.calculateWarmStorage(warmDataSize)
	response.TotalColdStorage = r.calculateColdStorage(coldDataSize)

	// Add calculation strings for warm/cold storage
	if response.TotalWarmStorage > 0 {
		response.TotalWarmStorageCalc = fmt.Sprintf("warmDataSize:%.2f * (1 + expansionRate:%d/100)",
			warmDataSize, r.ExpansionRate)
	}
	if response.TotalColdStorage > 0 {
		response.TotalColdStorageCalc = fmt.Sprintf("coldDataSize:%.2f (raw storage, no expansion)", coldDataSize)
	}

	if r.logger != nil {
		r.logger.Debug("Calculated storage requirements",
			zap.Int("totalActiveShards", response.TotalActiveShards),
			zap.Float64("totalHotStorage", response.TotalHotStorage),
			zap.Float64("totalWarmStorage", response.TotalWarmStorage),
			zap.Float64("totalColdStorage", response.TotalColdStorage),
			zap.Float64("storageMultiplier", multiplier))
	}

	response.ClusterConfigs = r.GetClusterConfigs(response.TotalActiveShards, response.TotalHotStorage, response.TotalWarmStorage, response.TotalColdStorage)

	if r.logger != nil {
		r.logger.Info("Completed search estimate calculation",
			zap.Int("totalActiveShards", response.TotalActiveShards),
			zap.Float64("totalHotStorage", response.TotalHotStorage),
			zap.Float64("totalWarmStorage", response.TotalWarmStorage),
			zap.Float64("totalColdStorage", response.TotalColdStorage),
			zap.Int("clusterConfigCount", len(response.ClusterConfigs)))
	}

	return
}

// Normalize sets default values for SearchEstimateRequest fields if they are not specified. It defaults pricing type to on-demand,
// target shard size to 25 and segments to 100. It also handles the config groups and normalizes region input for backward compatibility.
func (r *SearchEstimateRequest) Normalize() {
	// Normalize region input to canonical display name for cache lookup
	if r.Region != "" {
		r.Region = regions.NormalizeRegionInput(r.Region)
	}

	// if no pricing type is specified or not one of the values in PriceKeys array, default to on-demand
	if r.PricingType == "" || PriceOptionsMap[r.PricingType] == "" {
		r.PricingType = OnDemand
	}
	// if target shard size is not specified or zero, default to 25
	if r.TargetShardSize == 0 {
		r.TargetShardSize = 25
	}

	// Validate and normalize warm/cold tier percentages
	r.validateTierPercentages()

	// handle the config groups
	r.HandleConfigGroups()

	// When multi-AZ with standby is enabled, enforce minimum 2 replicas
	if r.MultiAzWithStandby && r.Replicas < 2 {
		r.Replicas = 2
	}
}

// GetClusterConfigFor takes HotNodes, WarmNodes, and ColdStorage objects and returns a ClusterConfig object.
// If the `DedicatedManager` flag is set to true, it will calculate the leader nodes
// required for the given hot nodes and add the leader nodes cost to the total cost.
// Warm nodes cost includes UltraWarm instance cost and managed storage cost.
// Cold storage cost is managed storage cost for S3.
// If the `Edp` field is set, it will calculate the discount based on the total cost
// and the Edp percentage, and then calculate the discounted total cost.
func (r *SearchEstimateRequest) GetClusterConfigFor(nodes *HotNodes, warmNodes *WarmNodes, coldStorage *ColdStorage) (cc ClusterConfig, err error) {
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

// IsInstanceTypesAllowed checks if the given instance type is allowed based on the InstanceTypes filter
func (r *SearchEstimateRequest) IsInstanceTypesAllowed(instanceType string) bool {
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

// calculateActivePrimaryShards calculates the number of active primary shards required
// for a given dataset size, target shard size and number of availability zones.
//
// The function takes into account the number of availability zones and ensures
// that the number of active primary shards is a multiple of the number of availability
// zones. If the calculated number of primary shards is not a multiple of the number
// of availability zones, it will round up to the nearest multiple.
//
// The function returns the calculated number of active primary shards.
func calculateActivePrimaryShards(dataSize float64, targetShardSize int, azs int) (ps int) {
	ps = int(math.Ceil(dataSize / float64(targetShardSize)))
	remainder := ps % azs
	if remainder != 0 {
		ps += azs - remainder
	}
	return
}

// GetClusterConfigs generates a list of cluster configurations based on the given totalActiveShards,
// totalStorage (hot tier), and warm/cold storage. It filters out instances that don't have enough JVM heap,
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
func (r *SearchEstimateRequest) GetClusterConfigs(totalActiveShards int, totalStorage float64, totalWarmStorage float64, totalColdStorage float64) (cc []ClusterConfig) {
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
				// Auto-select: evaluate all warm instance types to find best price
				warmInstanceTypesToEvaluate = AllWarmInstanceTypes
			}
		}

		for instanceType, instanceUnit := range nodes {
			//only process instances that have one of the instanceFamilies in the request
			if !r.IsInstanceTypesAllowed(instanceType) ||
				!instanceUnit.HasPricingOption(r.PricingType) {
				continue
			}

			if oldGenPattern.MatchString(instanceType) {
				continue
			}

			// if impl storage is preferred, only use instances with impl storage
			if r.PreferInternalStorage != nil && *r.PreferInternalStorage && !instanceUnit.Storage.HasInstanceStore() {
				continue
			}
			// if instance has not enough jvm heap, skip
			if instanceUnit.JVMMemory < r.MinimumJVM {
				continue
			}
			if nodeCount, storagePerNode := instanceUnit.GetRequiredNodeCount(requiredCPUs, int(totalStorage), r.Azs, r.StorageClass, r.PreferInternalStorage, 0.0, 0); nodeCount > 0 {
				nodeCount = int(math.Max(float64(nodeCount), float64(1+r.Replicas)))
				hotNodes := &HotNodes{
					Count:                 nodeCount,
					RequiredCPUs:          requiredCPUs,
					EstimatedActiveShards: totalActiveShards,
					AvailableCPUs:         nodeCount * instanceUnit.CPU,
					Type:                  instanceType,
					JVMMemoryPerNode:      instanceUnit.JVMMemory,
					JVMMemory:             float64(nodeCount) * instanceUnit.JVMMemory,
					Memory:                float64(nodeCount) * instanceUnit.Memory,
					Family:                instanceUnit.Family,
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

						warmNodes, warmNodeErr := r.selectWarmInstanceForSearchWithType(totalWarmStorage, warmInstanceType, nodes)
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
		cc = ScoreAndRank(cc, "search", r.getDataScale(), maxConfigs, 0)
	} else {
		if len(cc) > maxConfigs {
			cc = cc[:maxConfigs]
		}
	}
	return cc
}

// HandleConfigGroups will set the required fields on the SearchEstimateRequest
// based on a pre-defined config. The valid config values are "dev" and
// "production". The fields that are set are:
// - MinimumJVM
// - Azs
// - CPUsPerShard
// - DedicatedManager
func (r *SearchEstimateRequest) HandleConfigGroups() {
	config := strings.ToLower(r.Config)
	switch config {
	case "dev":
		r.MinimumJVM = 2
		r.Azs = 1
		r.CPUsPerShard = 1.0
		r.DedicatedManager = false
	case "production":
		r.MinimumJVM = 8
		r.Azs = 3
		r.CPUsPerShard = 1.5
		r.DedicatedManager = true
	}

}

// validateTierPercentages validates and normalizes the warm/cold tier percentages for search workloads
// Ensures that warm + cold <= 100%, scales proportionally if exceeded, and validates warm instance type
func (r *SearchEstimateRequest) validateTierPercentages() {
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

// isAutoSelectWarmInstance returns true if automatic warm instance selection is enabled for search workloads
// When AutoSelectWarmInstance is nil, it defaults to true
func (r *SearchEstimateRequest) isAutoSelectWarmInstance() bool {
	return r.AutoSelectWarmInstance == nil || *r.AutoSelectWarmInstance
}

// getStorageForTier returns the storage in GB for each tier based on percentages
func (r *SearchEstimateRequest) getStorageForTier(totalStorage float64) (hotStorage, warmStorage, coldStorage float64) {
	hotPercentage := 100 - r.WarmPercentage - r.ColdPercentage
	hotStorage = totalStorage * float64(hotPercentage) / 100
	warmStorage = totalStorage * float64(r.WarmPercentage) / 100
	coldStorage = totalStorage - hotStorage - warmStorage // Remaining to avoid rounding errors
	return
}

// calculateWarmStorage calculates the storage required for data in warm tier (UltraWarm)
// Warm tier stores data on SSD-backed managed storage
// Note: Replicas are NOT applied to warm storage - UltraWarm manages its own replication
func (r *SearchEstimateRequest) calculateWarmStorage(warmDataSizeGB float64) float64 {
	if warmDataSizeGB == 0 {
		return 0
	}

	// Apply index expansion rate for overhead
	expansionMultiplier := 1 + float64(r.ExpansionRate)/100.0

	// Note: No replica multiplier for warm storage - UltraWarm handles replication internally
	return math.Round(warmDataSizeGB*expansionMultiplier*100) / 100
}

// calculateColdStorage calculates the storage required for data in cold tier (S3)
// Cold tier stores raw data in S3 for archival
// Note: Replicas are NOT applied to cold storage - S3 manages its own durability
func (r *SearchEstimateRequest) calculateColdStorage(coldDataSizeGB float64) float64 {
	if coldDataSizeGB == 0 {
		return 0
	}

	// Note: No replica multiplier or expansion for cold storage - S3 handles durability internally
	return math.Round(coldDataSizeGB*100) / 100
}

// selectWarmInstanceForSearchWithType creates WarmNodes configuration for a specific warm instance type
// This is used when generating multiple configurations with different warm instance types for price comparison
// Supports both UltraWarm (ultrawarm1.medium/large) and OI2 instances as warm nodes
// hotInstances is used to look up OI2 storage sizes from pricing API
func (r *SearchEstimateRequest) selectWarmInstanceForSearchWithType(totalWarmStorage float64, warmInstanceType string, hotInstances map[string]price.InstanceUnit) (*WarmNodes, error) {
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
