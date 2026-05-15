// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"fmt"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/cache"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/commons"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/instances"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
	"go.uber.org/zap"
	"math"
	"sort"
	"strings"
)

const DropReplicaForNonActiveShards = "DROP_REPLICA_FOR_NONACTIVE_SHARDS"

type RemoteStorageOptions struct {
	Type string `json:"type"`
}

type TimeSeriesEstimateRequest struct {
	IngestionSize         float64 `json:"size"`
	HotRetentionPeriod    int     `json:"hotRetentionPeriod"`
	WarmRetentionPeriod   int     `json:"warmRetentionPeriod"`
	ColdRetentionPeriod   int     `json:"coldRetentionPeriod"`
	TargetShardSize       int     `json:"targetShardSize"`
	Azs                   int     `json:"azs"`
	Replicas              int     `json:"replicas"`
	CPUsPerShard          float32 `json:"CPUsPerShard"`
	PreferInternalStorage *bool   `json:"preferInternalStorage,omitempty"`
	DedicatedManager      bool    `json:"dedicatedManager"`
	StorageClass          string  `json:"storageClass,omitempty"`
	FreeStorageRequired   int     `json:"freeStorageRequired,omitempty"`
	ExpansionRate         int     `json:"indexExpansionRate,omitempty"`
	ActivePrimaryShards   int     `json:"activePrimaryShards,omitempty"`
	// Storage compression options
	DerivedSource   bool                 `json:"derivedSource,omitempty"`   // Enable derived source (~30% storage reduction)
	ZstdCompression bool                 `json:"zstdCompression,omitempty"` // Enable ZSTD compression (~20% storage reduction)
	PricingType     string               `json:"pricingType"`
	Edp             float64              `json:"edp"`
	Region          string               `json:"region"`
	MinimumJVM      float64              `json:"minimumJVM,omitempty"`
	RemoteStorage   RemoteStorageOptions `json:"remoteStorage,omitempty"`
	Config          string               `json:"config"`
	InstanceTypes   []string             `json:"instanceTypes"`
	// Multi-AZ with Standby option - when enabled, 1/3 of instances are standby and don't take traffic
	// Thread calculations use only 2/3 of instance count, and minimum 2 replicas are enforced
	MultiAzWithStandby bool `json:"multiAzWithStandby,omitempty"`
	// Warm instance type selection (optional - if not specified, auto-select based on storage size)
	WarmInstanceType       string `json:"warmInstanceType,omitempty"`       // "ultrawarm1.medium.search" or "ultrawarm1.large.search"
	AutoSelectWarmInstance *bool  `json:"autoSelectWarmInstance,omitempty"` // Default true - auto-select warm instance type
	DynamicSizing          bool   `json:"dynamicSizing,omitempty"`          // Enable workload-aware configuration scoring instead of cheapest-first ranking
	logger                 *zap.Logger
}

// isAutoSelectWarmInstance returns true if auto-select is enabled (default behavior)
func (r *TimeSeriesEstimateRequest) isAutoSelectWarmInstance() bool {
	return r.AutoSelectWarmInstance == nil || *r.AutoSelectWarmInstance
}

// GetDefaultTimeSeriesRequest returns a TimeSeriesEstimateRequest with default values.
//
// It creates a TimeSeriesEstimateRequest with default values for the fields:
// - TargetShardSize: 45
// - Azs: 3
// - Replicas: 1
// - CPUsPerShard: 1.25
// - FreeStorageRequired: 25
// - DedicatedManager: true
// - ExpansionRate: 10
// - MinimumJVM: 0
// - Edp: 0.0
// - Region: "US East (N. Virginia)"
// - StorageClass: "gp3"
// - InstanceFamily: []string{"Memory optimized"}
//
// The function returns a pointer to a TimeSeriesEstimateRequest.
func GetDefaultTimeSeriesRequest() *TimeSeriesEstimateRequest {
	return &TimeSeriesEstimateRequest{
		TargetShardSize:     45,
		Azs:                 3,
		Replicas:            1,
		CPUsPerShard:        1.25,
		FreeStorageRequired: 25,
		DedicatedManager:    true,
		ExpansionRate:       10,
		MinimumJVM:          0,
		Edp:                 0.0,
		Region:              "US East (N. Virginia)",
		StorageClass:        "gp3",
	}
}

// getDataScale returns the data scale tier based on total hot storage volume.
func (r *TimeSeriesEstimateRequest) getDataScale() string {
	totalHot := r.IngestionSize * float64(r.HotRetentionPeriod)
	switch {
	case totalHot < 500:
		return "small"
	case totalHot < 5000:
		return "medium"
	case totalHot < 50000:
		return "large"
	default:
		return "xlarge"
	}
}

// Calculate calculates the EstimationResponse for a given TimeSeriesEstimateRequest.
//
// It calculates the required shards and memory for the time series collection and the required shards and memory for the ingest pipeline.
// It also calculates the price for the required resources.
//
// The function returns the calculated EstimationResponse.
func (r *TimeSeriesEstimateRequest) Calculate() (response EstimateResponse, err error) {
	if r.logger != nil {
		r.logger.Info("Starting time series estimate calculation",
			zap.Float64("ingestionSize", r.IngestionSize),
			zap.String("usecase", "Time Series"),
			zap.Int("hotRetentionPeriod", r.HotRetentionPeriod),
			zap.Int("warmRetentionPeriod", r.WarmRetentionPeriod),
			zap.Int("coldRetentionPeriod", r.ColdRetentionPeriod),
			zap.Int("targetShardSize", r.TargetShardSize),
			zap.Int("azs", r.Azs),
			zap.Int("replicas", r.Replicas),
			zap.String("region", r.Region))
	}

	response.TimeSeriesRequest = r
	// if active primary shards is not specified or zero, calculate it in response
	response.ActivePrimaryShards = r.ActivePrimaryShards
	if response.ActivePrimaryShards <= 0 {
		response.ActivePrimaryShards = calculateActivePrimaryShards(r.IngestionSize*float64(1+r.ExpansionRate/100), r.TargetShardSize, r.Azs)
		if r.logger != nil {
			r.logger.Debug("Calculated active primary shards",
				zap.Int("activePrimaryShards", response.ActivePrimaryShards),
				zap.Float64("ingestionSize", r.IngestionSize),
				zap.Int("targetShardSize", r.TargetShardSize),
				zap.Int("azs", r.Azs))
		}
	} else {
		if r.logger != nil {
			r.logger.Debug("Using provided active primary shards",
				zap.Int("activePrimaryShards", response.ActivePrimaryShards))
		}
	}

	response.TotalActiveShards = response.ActivePrimaryShards * (1 + r.Replicas)
	multiplier := 1 + float64(r.FreeStorageRequired+r.ExpansionRate)/100.0

	// Calculate compression multiplier if derived source or ZSTD compression is enabled
	compressionMultiplier := commons.GetCompressionMultiplier(r.DerivedSource, r.ZstdCompression, commons.TimeSeriesCompressionRatios)

	// Calculate TotalHotStorage with explicit formula
	response.TotalHotStorageCalc = "math.Round(ingestionSize:" +
		formatFloat(r.IngestionSize) + "*(1+(FreeStorage:" +
		formatInt(r.FreeStorageRequired) + "+expansionRate:" +
		formatInt(r.ExpansionRate) + ")/100)*(1+(replica:" +
		formatInt(r.Replicas) + "))*hotRetentionPeriod:" +
		formatInt(r.HotRetentionPeriod) + ")"

	response.TotalHotStorage = math.Round(r.IngestionSize*multiplier*(1.0+float64(r.Replicas))*float64(r.HotRetentionPeriod)*compressionMultiplier*100) / 100

	if r.logger != nil {
		r.logger.Debug("Calculated hot storage",
			zap.Float64("totalHotStorage", response.TotalHotStorage),
			zap.Float64("storageMultiplier", multiplier),
			zap.Int("totalActiveShards", response.TotalActiveShards))
	}

	if r.RemoteStorage.Type == DropReplicaForNonActiveShards &&
		r.HotRetentionPeriod > 1 {
		// if OR1 is in instance family

		// Calculate TotalOptimizedHotStorage with explicit formula
		response.TotalOptimizedHotStorageCalc = "ingestionSize:" +
			formatFloat(r.IngestionSize) + "*(1+(FreeStorage:" +
			formatInt(r.FreeStorageRequired) + "+expansionRate:" +
			formatInt(r.ExpansionRate) + ")/100)*(1+(replica:" +
			formatInt(r.Replicas) + "))*1 + ingestionSize:" +
			formatFloat(r.IngestionSize) + "*(1+(FreeStorage:" +
			formatInt(r.FreeStorageRequired) + "+expansionRate:" +
			formatInt(r.ExpansionRate) + ")/100)*1*(hotRetentionPeriod:" +
			formatInt(r.HotRetentionPeriod) + "-1)"

		response.TotalOptimizedHotStorage = r.getOptimizedHotStorage()
		if r.logger != nil {
			r.logger.Debug("Calculated optimized hot storage for remote storage",
				zap.Float64("totalOptimizedHotStorage", response.TotalOptimizedHotStorage),
				zap.String("remoteStorageType", r.RemoteStorage.Type))
		}
	}

	if r.WarmRetentionPeriod > 0 {
		// Calculate TotalWarmStorage with explicit formula
		response.TotalWarmStorageCalc = "ingestionSize:" +
			formatFloat(r.IngestionSize) + "*(1+(expansionRate:" +
			formatInt(r.ExpansionRate) + ")/100)*warmRetentionPeriod:" +
			formatInt(r.WarmRetentionPeriod)

		response.TotalWarmStorage = r.IngestionSize * (1 + float64(r.ExpansionRate)/100.0) * float64(r.WarmRetentionPeriod) * compressionMultiplier
		if r.logger != nil {
			r.logger.Debug("Calculated warm storage",
				zap.Float64("totalWarmStorage", response.TotalWarmStorage),
				zap.Int("warmRetentionPeriod", r.WarmRetentionPeriod))
		}
	}

	if r.ColdRetentionPeriod > 0 {
		// Calculate TotalColdStorage with explicit formula
		response.TotalColdStorageCalc = "ingestionSize:" +
			formatFloat(r.IngestionSize) + "*(1+(expansionRate:" +
			formatInt(r.ExpansionRate) + ")/100)*coldRetentionPeriod:" +
			formatInt(r.ColdRetentionPeriod)

		response.TotalColdStorage = r.IngestionSize * (1 + float64(r.ExpansionRate)/100.0) * float64(r.ColdRetentionPeriod) * compressionMultiplier
		if r.logger != nil {
			r.logger.Debug("Calculated cold storage",
				zap.Float64("totalColdStorage", response.TotalColdStorage),
				zap.Int("coldRetentionPeriod", r.ColdRetentionPeriod))
		}
	}

	response.ClusterConfigs = r.GetClusterConfigs(response)

	if r.logger != nil {
		r.logger.Info("Completed time series estimate calculation",
			zap.Int("totalActiveShards", response.TotalActiveShards),
			zap.Float64("totalHotStorage", response.TotalHotStorage),
			zap.Float64("totalWarmStorage", response.TotalWarmStorage),
			zap.Float64("totalColdStorage", response.TotalColdStorage),
			zap.Int("clusterConfigCount", len(response.ClusterConfigs)))
	}

	return
}

// Normalize sets default values for TimeSeriesEstimateRequest fields if they are not specified. It defaults pricing type to on-demand,
// target shard size to 50 and segments to 100. It also normalizes region input for backward compatibility.
func (r *TimeSeriesEstimateRequest) Normalize() {
	// Normalize region input to canonical display name for cache lookup
	if r.Region != "" {
		r.Region = regions.NormalizeRegionInput(r.Region)
	}

	// if no pricing type is specified or not one of the values in PriceKeys array, default to on-demand
	if r.PricingType == "" || PriceOptionsMap[r.PricingType] == "" {
		r.PricingType = OnDemand
	}
	// if target shard size is not specified or zero, default to 50
	if r.TargetShardSize <= 0 {
		r.TargetShardSize = 50
	}
	r.HandleConfigGroups()

	// When multi-AZ with standby is enabled, enforce minimum 2 replicas
	if r.MultiAzWithStandby && r.Replicas < 2 {
		r.Replicas = 2
	}
}

// GetClusterConfigs generates a list of cluster configurations based on the given totalActiveShards,
// totalStorage, and totalVectorMemoryRequired. It filters out instances that don't have enough JVM heap,
// or don't have the preferred storage type. It also filters out old generation instances and burstable
// instances. It then calculates the required node count for each instance type, and creates a ClusterConfig
// for each instance type. The ClusterConfig is then sorted by TotalCost, and only the top 10 are returned.
// If there are less than 10 ClusterConfigs, all of them are returned.
func (r *TimeSeriesEstimateRequest) GetClusterConfigs(response EstimateResponse) (cc []ClusterConfig) {
	requiredCPUs := int(math.Ceil(float64(response.TotalActiveShards) * float64(r.CPUsPerShard)))
	provisionedRegion, err := cache.GetRegionProvisionedPrice(r.Region)
	if err == nil {
		nodes := provisionedRegion.HotInstances

		// Determine which warm instance types to evaluate
		var warmInstanceTypesToEvaluate []string
		if r.WarmRetentionPeriod > 0 {
			if r.WarmInstanceType != "" && !r.isAutoSelectWarmInstance() {
				warmInstanceTypesToEvaluate = []string{r.WarmInstanceType}
			} else {
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
			var hotStorageRequired = int(response.TotalHotStorage)
			if instanceUnit.HasRemoteStorage() &&
				r.RemoteStorage.Type == DropReplicaForNonActiveShards &&
				r.HotRetentionPeriod > 1 {
				hotStorageRequired = int(response.TotalOptimizedHotStorage)
			}
			if nodeCount, storagePerNode := instanceUnit.GetRequiredNodeCount(requiredCPUs, hotStorageRequired, r.Azs, r.StorageClass, r.PreferInternalStorage, 0.0, 0); nodeCount > 0 {
				nodeCount = int(math.Max(float64(nodeCount), float64(1+r.Replicas)))
				hotNodes := &HotNodes{
					Count:                 nodeCount,
					RequiredCPUs:          requiredCPUs,
					EstimatedActiveShards: response.TotalActiveShards,
					AvailableCPUs:         nodeCount * instanceUnit.CPU,
					JVMMemoryPerNode:      instanceUnit.JVMMemory,
					JVMMemory:             float64(nodeCount) * instanceUnit.JVMMemory,
					Memory:                float64(nodeCount) * instanceUnit.Memory,
					Type:                  instanceType,
					Family:                instanceUnit.Family,
					StorageRequired:       storagePerNode,
					HasInternalStorage:    instanceUnit.Storage.HasInstanceStore(),
				}
				if hotNodes.HasRemoteStorage() {
					hotNodes.RemoteStorageRequired = float64(storagePerNode / (1 + r.FreeStorageRequired/100.0))
				}
				hotNodes.CalculateMetricsWithStandby(r.MultiAzWithStandby)
				hotNodes.CalculatePrice(instanceUnit, provisionedRegion.GetStorageUnitPrice(r.StorageClass), provisionedRegion.GetStorageUnitPrice("managedStorage"))

				if r.WarmRetentionPeriod > 0 {
					// Generate configs for each warm instance type to compare prices
					for _, warmInstanceType := range warmInstanceTypesToEvaluate {
						// OI2 warm instances can only be used with OpenSearch Optimized hot nodes
						if isOI2WarmInstance(warmInstanceType) && !isOpenSearchOptimizedInstance(instanceType) {
							continue
						}

						warmNodes, warmNodeErr := r.selectWarmInstanceForTimeSeriesWithType(
							response.TotalWarmStorage, warmInstanceType, instanceType, hotNodes.Count, nodes)
						if warmNodeErr != nil {
							continue
						}
						if warmNodes != nil {
							// Get warm instance pricing
							// OI2 instances use hot instance pricing, UltraWarm uses warm instance pricing
							var warmInstanceUnit price.InstanceUnit
							if isOI2WarmInstance(warmNodes.Type) {
								if iu, found := nodes[warmNodes.Type]; found {
									warmInstanceUnit = iu
								} else {
									continue
								}
							} else {
								warmInstanceUnitPtr, pricingErr := provisionedRegion.GetWarmNode(warmNodes.Type)
								if pricingErr != nil {
									continue
								}
								warmInstanceUnit = *warmInstanceUnitPtr
							}
							warmNodes.AvailableCPUs = warmInstanceUnit.CPU * warmNodes.Count
							warmNodes.CalculateMetrics()
							warmNodes.CalculatePrice(warmInstanceUnit, provisionedRegion.GetStorageUnitPrice("managedStorage"), isOI2WarmInstance(warmNodes.Type))
						}

						// Cold storage (same logic, inside the warm loop)
						var coldSettings *ColdStorage
						if r.ColdRetentionPeriod > 0 {
							coldSettings = &ColdStorage{}
							coldSettings.StorageRequired = response.TotalColdStorage
							coldSettings.ManagedStorageCost = response.TotalColdStorage * provisionedRegion.GetStorageUnitPrice("managedStorage")
						}

						newClusterConfig, err := r.GetClusterConfigFor(hotNodes, warmNodes, coldSettings)
						if err != nil {
							continue
						}
						if newClusterConfig.ColdStorage != nil {
							newClusterConfig.InfrastructureCost = newClusterConfig.TotalCost - newClusterConfig.ColdStorage.ManagedStorageCost
							compressionMult := commons.GetCompressionMultiplier(r.DerivedSource, r.ZstdCompression, commons.TimeSeriesCompressionRatios)
							newClusterConfig.ColdStorageRampUp = calculateColdStorageRampUp(
								r.HotRetentionPeriod, r.WarmRetentionPeriod, r.ColdRetentionPeriod,
								r.IngestionSize, r.ExpansionRate, compressionMult,
								provisionedRegion.GetStorageUnitPrice("managedStorage"),
							)
						}
						cc = append(cc, newClusterConfig)
					}
				} else {
					// No warm storage: config without warm, but possibly with cold
					var coldSettings *ColdStorage
					if r.ColdRetentionPeriod > 0 {
						coldSettings = &ColdStorage{}
						coldSettings.StorageRequired = response.TotalColdStorage
						coldSettings.ManagedStorageCost = response.TotalColdStorage * provisionedRegion.GetStorageUnitPrice("managedStorage")
					}

					newClusterConfig, err := r.GetClusterConfigFor(hotNodes, nil, coldSettings)
					if err != nil {
						continue
					}
					if newClusterConfig.ColdStorage != nil {
						newClusterConfig.InfrastructureCost = newClusterConfig.TotalCost - newClusterConfig.ColdStorage.ManagedStorageCost
						compressionMult := commons.GetCompressionMultiplier(r.DerivedSource, r.ZstdCompression, commons.TimeSeriesCompressionRatios)
						newClusterConfig.ColdStorageRampUp = calculateColdStorageRampUp(
							r.HotRetentionPeriod, r.WarmRetentionPeriod, r.ColdRetentionPeriod,
							r.IngestionSize, r.ExpansionRate, compressionMult,
							provisionedRegion.GetStorageUnitPrice("managedStorage"),
						)
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
		oversampleLimit := maxConfigs * 3
		if len(cc) > oversampleLimit {
			cc = cc[:oversampleLimit]
		}
		cc = ScoreAndRank(cc, "timeSeries", r.getDataScale(), maxConfigs, 0)
	} else {
		if len(cc) > maxConfigs {
			cc = cc[:maxConfigs]
		}
	}
	return cc
}

// calculateColdStorageRampUp computes the monthly cold storage costs during the ramp-up period.
// Cold storage is empty on day 1 — data only enters cold after hot+warm retention expires.
// The cost ramps up linearly over the cold retention period before reaching steady state.
func calculateColdStorageRampUp(hotRetention, warmRetention, coldRetention int, ingestionSize float64, expansionRate int, compressionMultiplier float64, pricePerGBMonth float64) *ColdStorageRampUp {
	if coldRetention <= 0 {
		return nil
	}

	warmUpDays := hotRetention + warmRetention
	dailyColdDataGB := ingestionSize * (1 + float64(expansionRate)/100.0) * compressionMultiplier
	daysPerMonth := price.DaysPerMonth
	steadyStateMonth := int(math.Ceil(float64(warmUpDays+coldRetention) / daysPerMonth))

	monthlyCosts := make([]float64, steadyStateMonth)
	for m := 1; m <= steadyStateMonth; m++ {
		dayAtEndOfMonth := float64(m) * daysPerMonth
		daysInCold := math.Max(0, math.Min(float64(coldRetention), dayAtEndOfMonth-float64(warmUpDays)))
		monthlyCosts[m-1] = math.Round(daysInCold*dailyColdDataGB*pricePerGBMonth*100) / 100
	}

	return &ColdStorageRampUp{
		WarmUpDays:        warmUpDays,
		DailyColdDataGB:   math.Round(dailyColdDataGB*100) / 100,
		ColdRetentionDays: coldRetention,
		PricePerGBMonth:   pricePerGBMonth,
		SteadyStateMonth:  steadyStateMonth,
		MonthlyCosts:      monthlyCosts,
	}
}

// selectWarmInstanceForTimeSeriesWithType creates a WarmNodes object for a specific warm instance type.
// It calculates the required node count, validates against AZ-based and instance-specific limits,
// and returns the WarmNodes or an error if constraints are violated.
func (r *TimeSeriesEstimateRequest) selectWarmInstanceForTimeSeriesWithType(
	totalWarmStorage float64,
	warmInstanceType string,
	hotInstanceType string,
	hotNodeCount int,
	nodes map[string]price.InstanceUnit,
) (*WarmNodes, error) {
	if totalWarmStorage == 0 {
		return nil, nil
	}

	cacheSize := getWarmInstanceCacheSize(warmInstanceType, nodes)
	if cacheSize == 0 {
		return nil, fmt.Errorf("unknown warm instance type: %s", warmInstanceType)
	}

	warmNodes := &WarmNodes{
		Type: warmInstanceType,
	}
	warmNodes.Count = int(math.Max(math.Ceil(totalWarmStorage/cacheSize), 2.0))

	var maxWarmNodes int
	if isOI2WarmInstance(warmInstanceType) {
		maxWarmNodes = 1002 * r.Azs
	} else {
		maxWarmNodes = 250 * r.Azs
	}

	if warmNodes.Count > maxWarmNodes {
		return nil, fmt.Errorf("warm storage %.2f GB requires %d %s nodes, exceeding maximum %d for %d AZ(s)",
			totalWarmStorage, warmNodes.Count, warmInstanceType, maxWarmNodes, r.Azs)
	}

	ebsStorage, found := instances.InstanceLimitsMap[hotInstanceType]
	if found && ebsStorage.MaxNodesCount > 0 {
		if hotNodeCount+warmNodes.Count > ebsStorage.MaxNodesCount {
			return nil, fmt.Errorf("total node count %d exceeds instance limit %d",
				hotNodeCount+warmNodes.Count, ebsStorage.MaxNodesCount)
		}
	}

	warmNodes.StorageRequired = int(totalWarmStorage)
	return warmNodes, nil
}

// getOptimizedHotStorage calculates the optimized hot storage required for the given TimeSeriesEstimateRequest.
// It takes into account the free storage required, expansion rate, replicas, and hot retention period.
// It also applies compression if derived source or ZSTD compression is enabled.
// It returns the total hot storage required, rounded to the nearest 100.
func (r *TimeSeriesEstimateRequest) getOptimizedHotStorage() (hotStorageRequired float64) {
	multiplier := 1 + float64(r.FreeStorageRequired+r.ExpansionRate)/100.0
	compressionMultiplier := commons.GetCompressionMultiplier(r.DerivedSource, r.ZstdCompression, commons.TimeSeriesCompressionRatios)
	activeStorage := r.IngestionSize * multiplier * (1.0 + float64(r.Replicas))
	nonActiveStorage := r.IngestionSize * multiplier * float64(r.HotRetentionPeriod-1)
	hotStorageRequired = math.Round((activeStorage+nonActiveStorage)*compressionMultiplier*100) / 100
	return
}

// IsInstanceTypesAllowed checks if the given instance type is allowed based on the InstanceTypes filter
func (r *TimeSeriesEstimateRequest) IsInstanceTypesAllowed(instanceType string) bool {
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

// GetClusterConfigFor takes a HotNodes object, an optional WarmNodes object, and an optional ColdStorage object
// and returns a ClusterConfig object.
// If the `DedicatedManager` flag is set to true, it will calculate the leader nodes required for the given hot nodes
// and add the leader nodes cost to the total cost.
// If `WarmNodes` is not nil, it will add the warm nodes cost to the total cost.
// If `ColdStorage` is not nil, it will add the cold storage cost to the total cost.
// If the `Edp` field is set, it will calculate the discount based on the total cost and the Edp percentage,
// and then calculate the discounted total cost.
// If there is an error while calculating the leader nodes, it will return an empty ClusterConfig and an error.
func (r *TimeSeriesEstimateRequest) GetClusterConfigFor(nodes *HotNodes, warmNodes *WarmNodes, coldSettings *ColdStorage) (cc ClusterConfig, err error) {
	cc.HotNodes = nodes

	//calculate warm nodes
	cc.WarmNodes = warmNodes
	cc.ColdStorage = coldSettings

	//calculate leader nodes with hot nodes
	nodeCount := nodes.Count
	if warmNodes != nil {
		nodeCount += warmNodes.Count
	}

	cc.TotalCost = cc.HotNodes.Price[r.PricingType].TotalCost

	if r.DedicatedManager {
		cc.LeaderNodes, err = getLeaderNodesFor(nodes.Type, r.Region, nodeCount)
		// if there is an error, skip this node
		if err != nil {
			return
		}
		cc.TotalCost += cc.LeaderNodes.Price[r.PricingType].TotalCost
	}

	// OI2 warm instances support all pricing types, UltraWarm only supports OnDemand
	if cc.WarmNodes != nil {
		warmPricingType := OnDemand
		if isOI2WarmInstance(cc.WarmNodes.Type) {
			warmPricingType = r.PricingType
		}
		cc.TotalCost += cc.WarmNodes.Price[warmPricingType].TotalCost + cc.WarmNodes.ManagedStorageCost
	}
	if cc.ColdStorage != nil {
		cc.TotalCost += cc.ColdStorage.ManagedStorageCost
	}
	if r.Edp != 0 {
		cc.Edp = r.Edp
		cc.Discount = cc.TotalCost * (r.Edp / 100)
		cc.DiscountedTotal = cc.TotalCost - cc.Discount
	}
	return

}

// HandleConfigGroups sets the required fields on the TimeSeriesEstimateRequest
// based on a pre-defined config. The valid config values are "dev" and
// "production". The fields that are set are:
// - MinimumJVM
// - Azs
// - CPUsPerShard
// - DedicatedManager
func (r *TimeSeriesEstimateRequest) HandleConfigGroups() {
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
		r.CPUsPerShard = 1.25
		r.DedicatedManager = true
	}

}
