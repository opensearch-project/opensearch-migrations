// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"errors"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
	"go.uber.org/zap"
	"regexp"
	"strings"
)

var oldGenPattern = regexp.MustCompile(`^(r4|r3|r2|t2|m2|m3|m4|c3|c2|i2)\.*`)

type RegionsResponse struct {
	Regions []regions.RegionInfo `json:"regions"`
}

type PricingOptionsResponse struct {
	Options             *[]string      `json:"pricingOptions,omitempty"`
	NamedPricingOptions *[]NamedValues `json:"namedPricingOptions,omitempty"`
}

type NamedValues struct {
	Value string `json:"value"`
	Name  string `json:"name"`
}

type InstanceFamilyOptionsResponse struct {
	Options []string `json:"instanceFamilies"`
}

type InstanceTypesByFamilyResponse struct {
	InstanceTypes []InstanceTypeFamily `json:"instanceTypes"`
}

type InstanceTypeFamily struct {
	Family        string   `json:"family"`
	InstanceTypes []string `json:"instanceTypes"`
}

// WarmInstanceTypesResponse contains available warm instance types grouped by category
type WarmInstanceTypesResponse struct {
	WarmInstanceTypes []WarmInstanceTypeGroup `json:"warmInstanceTypes"`
}

// WarmInstanceTypeGroup represents a group of warm instance types (e.g., UltraWarm, OI2)
type WarmInstanceTypeGroup struct {
	Label       string             `json:"label"`
	Description string             `json:"description,omitempty"`
	Options     []WarmInstanceType `json:"options"`
}

// WarmInstanceType represents a single warm instance type option
type WarmInstanceType struct {
	Value                       string `json:"value"`
	Label                       string `json:"label"`
	Description                 string `json:"description"`
	RequiresOpenSearchOptimized bool   `json:"requiresOpenSearchOptimized"`
}

// GetWarmInstanceTypes returns all available warm instance types grouped by category
// This is the default function that returns all warm instance types without regional filtering
func GetWarmInstanceTypes() WarmInstanceTypesResponse {
	return GetWarmInstanceTypesForRegion(nil)
}

// GetWarmInstanceTypesForRegion returns warm instance types filtered by regional availability
// If hotInstances is nil, returns all warm instance types
// OI2 instances are only included if they exist in the hotInstances map (regional availability)
func GetWarmInstanceTypesForRegion(hotInstances map[string]price.InstanceUnit) WarmInstanceTypesResponse {
	// UltraWarm instances are always available (read-only warm tier)
	ultraWarmOptions := []WarmInstanceType{
		{
			Value:                       "ultrawarm1.medium.search",
			Label:                       "UltraWarm Medium (2 vCPUs, 15.5 GB memory, 1.5 TB cache)",
			Description:                 "Cost-optimized for up to 33 TB. Supports fewer concurrent users.",
			RequiresOpenSearchOptimized: false,
		},
		{
			Value:                       "ultrawarm1.large.search",
			Label:                       "UltraWarm Large (16 vCPUs, 122 GB memory, 20 TB cache)",
			Description:                 "Larger cache supports more concurrent users for 39+ TB workloads.",
			RequiresOpenSearchOptimized: false,
		},
	}

	// OI2 warm instance definitions (writable warm tier)
	// Cache size is 80% of instance storage per AWS documentation:
	// https://docs.aws.amazon.com/opensearch-service/latest/developerguide/limits.html
	oi2Definitions := []WarmInstanceType{
		{
			Value:                       "oi2.large.search",
			Label:                       "OI2 large (2 vCPUs, 16 GB memory, 375 GB cache)",
			Description:                 "Max addressable warm storage: 1.9 TB. Requires O-series hot nodes.",
			RequiresOpenSearchOptimized: true,
		},
		{
			Value:                       "oi2.xlarge.search",
			Label:                       "OI2 xlarge (4 vCPUs, 32 GB memory, 750 GB cache)",
			Description:                 "Max addressable warm storage: 3.8 TB. Requires O-series hot nodes.",
			RequiresOpenSearchOptimized: true,
		},
		{
			Value:                       "oi2.2xlarge.search",
			Label:                       "OI2 2xlarge (8 vCPUs, 64 GB memory, 1.5 TB cache)",
			Description:                 "Max addressable warm storage: 7.5 TB. Requires O-series hot nodes.",
			RequiresOpenSearchOptimized: true,
		},
		{
			Value:                       "oi2.4xlarge.search",
			Label:                       "OI2 4xlarge (16 vCPUs, 128 GB memory, 3 TB cache)",
			Description:                 "Max addressable warm storage: 15 TB. Requires O-series hot nodes.",
			RequiresOpenSearchOptimized: true,
		},
		{
			Value:                       "oi2.8xlarge.search",
			Label:                       "OI2 8xlarge (32 vCPUs, 256 GB memory, 6 TB cache)",
			Description:                 "Max addressable warm storage: 30 TB. Requires O-series hot nodes.",
			RequiresOpenSearchOptimized: true,
		},
	}

	// Filter OI2 instances by regional availability
	var oi2Options []WarmInstanceType
	if hotInstances == nil {
		// No regional filter - include all OI2 instances
		oi2Options = oi2Definitions
	} else {
		// Filter OI2 instances based on availability in the region
		for _, oi2 := range oi2Definitions {
			if _, found := hotInstances[oi2.Value]; found {
				oi2Options = append(oi2Options, oi2)
			}
		}
	}

	// Build response with available groups
	groups := []WarmInstanceTypeGroup{
		{
			Label:   "UltraWarm (Read-Only)",
			Options: ultraWarmOptions,
		},
	}

	// Only include OI2 group if there are available instances
	if len(oi2Options) > 0 {
		groups = append(groups, WarmInstanceTypeGroup{
			Label:       "Writable Warm (OI2) Requires O-series hot nodes",
			Description: "Writable warm tier. Requires O-series hot nodes (OR1, OR2, OM2, OI2).",
			Options:     oi2Options,
		})
	}

	return WarmInstanceTypesResponse{
		WarmInstanceTypes: groups,
	}
}

type EstimateRequest struct {
	Search     *SearchEstimateRequest     `json:"search,omitempty"`
	Vector     *VectorEstimateRequest     `json:"vector,omitempty"`
	TimeSeries *TimeSeriesEstimateRequest `json:"timeSeries,omitempty"`
	logger     *zap.Logger
}

const OnDemand = "OnDemand"

var PriceOptionsMap = map[string]string{
	OnDemand: "On Demand",
	"AURI1":  "All Upfront Reserved Instances - 1 year",
	"AURI3":  "All Upfront Reserved Instances - 3 years",
	"NURI1":  "No Upfront Reserved Instances - 1 year",
	"NURI3":  "No Upfront Reserved Instances - 3 years",
	"PURI1":  "Partial Upfront Reserved Instances - 1 year",
	"PURI3":  "Partial Upfront Reserved Instances - 3 years",
}
var UwPriceKeys = []string{OnDemand}

// OI2PriceKeys contains all pricing options available for OI2 warm instances
// OI2 instances support Reserved Instance pricing unlike UltraWarm
var OI2PriceKeys = []string{OnDemand, "AURI1", "AURI3", "NURI1", "NURI3", "PURI1", "PURI3"}

// MaxNodeCountPerCluster defines the maximum number of nodes allowed per cluster based on AZ configuration
var MaxNodeCountPerCluster = map[int]int{
	1: 334,  // 1 AZ configuration
	2: 668,  // 2 AZ configuration
	3: 1002, // 3 AZ configuration
}

var InstanceFamilies = []string{"General purpose", "Compute optimized", "Memory optimized", "Storage optimized", "OR1"}

type EstimateResponse struct {
	Currency                          string                     `json:"currency,omitempty"`
	SearchRequest                     *SearchEstimateRequest     `json:"searchRequest,omitempty"`
	TimeSeriesRequest                 *TimeSeriesEstimateRequest `json:"timeSeriesRequest,omitempty"`
	VectorRequest                     *VectorEstimateRequest     `json:"vectorRequest,omitempty"`
	ActivePrimaryShards               int                        `json:"totalHotShards,omitempty"`
	TotalActiveShards                 int                        `json:"totalActiveShards,omitempty"`
	TotalHotStorage                   float64                    `json:"totalHotStorage,omitempty"`
	TotalHotStorageCalc               string                     `json:"totalHotStorageCalc,omitempty"`
	TotalOptimizedHotStorage          float64                    `json:"totalOptimizedHotStorage,omitempty"`
	TotalOptimizedHotStorageCalc      string                     `json:"totalOptimizedHotStorageCalc,omitempty"`
	TotalWarmStorage                  float64                    `json:"totalWarmStorage,omitempty"`
	TotalWarmStorageCalc              string                     `json:"totalWarmStorageCalc,omitempty"`
	TotalColdStorage                  float64                    `json:"totalColdStorage,omitempty"`
	TotalColdStorageCalc              string                     `json:"totalColdStorageCalc,omitempty"`
	TotalMemoryRequiredForVectors     float64                    `json:"totalMemoryRequiredForVectors,omitempty"`
	TotalMemoryRequiredForVectorsCalc string                     `json:"totalMemoryRequiredForVectorsCalc,omitempty"`
	ClusterConfigs                    []ClusterConfig            `json:"clusterConfigs"`
}

type InstancePrice struct {
	SingleInstance         float64 `json:"singleInstance"`
	StorageCostPerInstance float64 `json:"storageCostPerInstance,omitempty"`
	RemoteStorageCost      float64 `json:"remoteStorageCost,omitempty"`
	TotalCost              float64 `json:"totalCost"`
}

type HotNodes struct {
	Type                     string                   `json:"type,omitempty"`
	AvailableCPUs            int                      `json:"availableCPUs,omitempty"`
	RequiredCPUs             int                      `json:"requiredCPUs,omitempty"`
	EstimatedActiveShards    int                      `json:"estimatedActiveShards,omitempty"`
	MaxNumberOfSearchThreads int                      `json:"maxNumberOfSearchThreads,omitempty"`
	MaxNumberOfWriteThreads  int                      `json:"maxNumberOfWriteThreads,omitempty"`
	Count                    int                      `json:"count,omitempty"`
	HasInternalStorage       bool                     `json:"hasInternalStorage,omitempty"`
	StorageRequired          int                      `json:"storageRequired,omitempty"`
	RemoteStorageRequired    float64                  `json:"remoteStorageRequired,omitempty"`
	JVMMemoryPerNode         float64                  `json:"JVMMemoryPerNode,omitempty"`
	VectorMemoryPerNode      float64                  `json:"vectorMemoryPerNode,omitempty"`
	JVMMemory                float64                  `json:"JVMMemory,omitempty"`
	Memory                   float64                  `json:"memory,omitempty"`
	VectorMemory             float64                  `json:"vectorMemory,omitempty"`
	Family                   string                   `json:"family,omitempty"`
	Price                    map[string]InstancePrice `json:"price,omitempty"`
}

type WarmNodes struct {
	Type                     string                   `json:"type,omitempty"`
	Count                    int                      `json:"count,omitempty"`
	AvailableCPUs            int                      `json:"availableCPUs,omitempty"`
	MaxNumberOfSearchThreads int                      `json:"maxNumberOfSearchThreads,omitempty"`
	MaxNumberOfWriteThreads  int                      `json:"maxNumberOfWriteThreads,omitempty"`
	StorageRequired          int                      `json:"storageRequired,omitempty"`
	ManagedStorageCost       float64                  `json:"managedStorageCost,omitempty"`
	Price                    map[string]InstancePrice `json:"price,omitempty"`
}

type ColdStorage struct {
	StorageRequired    float64 `json:"storageRequired,omitempty"`
	ManagedStorageCost float64 `json:"managedStorageCost,omitempty"`
}

type ColdStorageRampUp struct {
	WarmUpDays        int       `json:"warmUpDays"`
	DailyColdDataGB   float64   `json:"dailyColdDataGB"`
	ColdRetentionDays int       `json:"coldRetentionDays"`
	PricePerGBMonth   float64   `json:"pricePerGBMonth"`
	SteadyStateMonth  int       `json:"steadyStateMonth"`
	MonthlyCosts      []float64 `json:"monthlyCosts"`
}

type ClusterConfig struct {
	LeaderNodes        *HotNodes          `json:"leaderNodes,omitempty"`
	HotNodes           *HotNodes          `json:"hotNodes,omitempty"`
	WarmNodes          *WarmNodes         `json:"warmNodes,omitempty"`
	ColdStorage        *ColdStorage       `json:"coldStorage,omitempty"`
	Edp                float64            `json:"edp,omitempty"`
	Discount           float64            `json:"discount,omitempty"`
	DiscountedTotal    float64            `json:"discountedTotal,omitempty"`
	TotalCost          float64            `json:"totalCost,omitempty"`
	InfrastructureCost float64            `json:"infrastructureCost,omitempty"`
	ColdStorageRampUp  *ColdStorageRampUp `json:"coldStorageRampUp,omitempty"`
	Score              float64            `json:"score,omitempty"`
}

// Validate validates the fields of an EstimateRequest.
//
// It checks if exactly one of the search, time series or vector fields is set.
// If not, it returns an error.
//
// The function returns nil if the EstimateRequest is valid, an error otherwise.
func (er *EstimateRequest) Validate() error {
	if er.TimeSeries != nil && er.Search != nil && er.Vector != nil {
		return errors.New("send anyone of the time series, vector or search")
	}
	return nil
}

// Normalize normalizes the fields of an EstimateRequest.
//
// It normalizes the fields of `Search`, `TimeSeries` or `Vector` depending on which one is set.
// It also sets the logger in all of them.
//
// The function doesn't return any value.
func (er *EstimateRequest) Normalize(logger *zap.Logger) {
	if er.TimeSeries != nil {
		er.TimeSeries.Normalize()
		er.TimeSeries.logger = logger
	} else if er.Vector != nil {
		er.Vector.Normalize()
		er.Vector.Normalize()
	} else {
		er.Search.Normalize()
	}
	er.logger = logger
}

// Calculate calculates the EstimationResponse for a given EstimateRequest.
//
// If the EstimateRequest is for time series, it calculates the required shards and memory for the time series collection and the required shards and memory for the ingest pipeline.
// It also calculates the price for the required resources.
//
// If the EstimateRequest is for vector, it calculates the required shards and memory for the vector collection and the required shards and memory for the ingest pipeline.
// It also calculates the price for the required resources.
//
// If the EstimateRequest is for search, it calculates the required shards and memory for the search collection and the required shards and memory for the ingest pipeline.
// It also calculates the price for the required resources.
//
// The function returns the calculated EstimationResponse.
// GetCurrency returns the currency for the estimate based on the request region.
func (er *EstimateRequest) GetCurrency() string {
	var regionName string
	if er.Search != nil {
		regionName = er.Search.Region
	} else if er.Vector != nil {
		regionName = er.Vector.Region
	} else if er.TimeSeries != nil {
		regionName = er.TimeSeries.Region
	}
	if regions.IsChinaRegionDisplay(regionName) {
		return "CNY"
	}
	return "USD"
}

func (er *EstimateRequest) Calculate() (response EstimateResponse, err error) {
	if er.TimeSeries != nil {
		return er.TimeSeries.Calculate()
	} else if er.Vector != nil {
		return er.Vector.Calculate()
	}
	return er.Search.Calculate()
}

// CalculatePrice calculates the price for the given hot nodes based on the given instance unit and storage price.
//
// It calculates the price for each price option in PriceOptionsMap, and stores the results in the Price field of the hot nodes.
//
// The price is calculated as follows:
// - The hourly cost is the price of the instance per hour.
// - The monthly cost is the hourly cost multiplied by 24 hours a day, and the number of days in a month.
// - The yearly cost is the hourly cost multiplied by 24 hours a day, the number of days in a month, and 12 months in a year.
// - The prepaid cost is the price of the instance for the given pricing option, which is either On Demand, or one of the reserved instance pricing options.
// - The total cost is the prepaid cost plus the monthly cost of the storage, multiplied by the number of nodes.
func (hn *HotNodes) CalculatePrice(iu price.InstanceUnit, storagePrice float64, managedStoragePrice float64) {
	priceMap := make(map[string]InstancePrice)
	for priceOption := range PriceOptionsMap {
		ip := hn.getInstancePrice(priceOption, iu, storagePrice, managedStoragePrice)
		priceMap[priceOption] = *ip
	}
	hn.Price = priceMap
}

// getInstancePrice returns the price for a given hot nodes and instance unit.
//
// It returns an InstancePrice with the single instance cost, the storage cost per instance, and the remote storage cost,
// and the total cost, which is the single instance cost plus the storage cost per instance plus the remote storage cost, multiplied by the number of nodes.
func (hn *HotNodes) getInstancePrice(priceType string, iu price.InstanceUnit, storagePrice float64, managedStorage float64) *InstancePrice {
	yearly := strings.HasSuffix(priceType, "1")
	prepaidCost := iu.Price[priceType+"PC"].Price
	hourlyCost := iu.Price[priceType+"HC"].Price
	var monthlyCost float64
	switch {
	case priceType == "OnDemand":
		monthlyCost = iu.Price["OnDemand"].Price * 24 * price.DaysPerMonth
	case strings.HasPrefix(priceType, "AURI"):
		if yearly {
			monthlyCost = prepaidCost / 12
		} else {
			monthlyCost = prepaidCost / 36
		}
	case strings.HasPrefix(priceType, "NURI"):
		monthlyCost = hourlyCost * 24 * price.DaysPerMonth
	default:
		if yearly {
			monthlyCost = prepaidCost/12 + hourlyCost*24*price.DaysPerMonth
		} else {
			monthlyCost = prepaidCost/36 + hourlyCost*24*price.DaysPerMonth
		}
	}
	storageCostPerInstance := 0.0
	if !iu.Storage.HasInstanceStore() && storagePrice > 0 {
		storageCostPerInstance = float64(hn.StorageRequired) * storagePrice
	}
	remoteStorageCost := 0.0
	if hn.HasRemoteStorage() {
		remoteStorageCost = hn.RemoteStorageRequired * managedStorage
	}
	ip := &InstancePrice{
		SingleInstance:         monthlyCost,
		StorageCostPerInstance: storageCostPerInstance,
		RemoteStorageCost:      remoteStorageCost,
	}
	ip.TotalCost = (ip.StorageCostPerInstance + ip.SingleInstance + ip.RemoteStorageCost) * float64(hn.Count)
	return ip
}

// CalculateMetrics calculates the maximum number of write threads and search threads that can be used for the given hot nodes.
//
// The maximum number of write threads is the available CPUs.
//
// The maximum number of search threads is calculated as (availableCPUs * 3) / 2 + count.
// This is done to ensure that we don't overprovision the search threads and run out of memory.
//
// When multiAzWithStandby is true, thread calculations are based on 2/3 of the instance count,
// since 1/3 of instances are standby and don't take traffic.
func (hn *HotNodes) CalculateMetrics() {
	hn.MaxNumberOfWriteThreads = hn.AvailableCPUs
	hn.MaxNumberOfSearchThreads = ((hn.AvailableCPUs * 3) / 2) + hn.Count
}

// CalculateMetricsWithStandby calculates thread metrics accounting for multi-AZ with standby.
// When multiAzWithStandby is true, only 2/3 of instances take traffic, so thread calculations
// are based on the effective (active) CPUs and instance count.
func (hn *HotNodes) CalculateMetricsWithStandby(multiAzWithStandby bool) {
	if multiAzWithStandby {
		// Only 2/3 of instances take traffic in multi-AZ with standby configuration
		effectiveCount := (hn.Count * 2) / 3
		if effectiveCount < 1 {
			effectiveCount = 1
		}
		// Calculate effective CPUs based on the ratio of effective instances
		effectiveCPUs := (hn.AvailableCPUs * 2) / 3
		if effectiveCPUs < 1 {
			effectiveCPUs = 1
		}
		hn.MaxNumberOfWriteThreads = effectiveCPUs
		hn.MaxNumberOfSearchThreads = ((effectiveCPUs * 3) / 2) + effectiveCount
	} else {
		hn.MaxNumberOfWriteThreads = hn.AvailableCPUs
		hn.MaxNumberOfSearchThreads = ((hn.AvailableCPUs * 3) / 2) + hn.Count
	}
}

// HasRemoteStorage checks if the given HotNodes instance family is OR1.
//
// If true, it means the instance has remote storage.
func (hn *HotNodes) HasRemoteStorage() (hasRemoteStorage bool) {
	//if instance family is OR1 return true
	if hn.Family == "OR1" {
		hasRemoteStorage = true
	}
	return
}

// CalculatePrice calculates the price for the given WarmNodes and instance unit.
//
// It calculates the price for each price option based on instance type:
// - OI2 instances: All pricing options (OnDemand, Reserved Instances)
// - UltraWarm instances: OnDemand pricing only
//
// The price is calculated as follows:
// - The monthly cost is the price of the instance per hour, multiplied by 24 hours a day, and the number of days in a month.
// - The prepaid cost is the price of the instance for the given pricing option, which is either On Demand, or one of the reserved instance pricing options.
// - The managed storage cost is the price of the managed storage per GB, multiplied by the storage required.
// - The total cost is the prepaid cost plus the monthly cost of the managed storage, multiplied by the number of nodes.
func (hn *WarmNodes) CalculatePrice(iu price.InstanceUnit, storagePrice float64, isOI2 bool) {
	priceMap := make(map[string]InstancePrice)
	priceKeys := UwPriceKeys
	if isOI2 {
		priceKeys = OI2PriceKeys
	}
	for _, priceKey := range priceKeys {
		ip := hn.getInstancePrice(priceKey, iu)
		priceMap[priceKey] = *ip
	}
	hn.ManagedStorageCost = float64(hn.StorageRequired) * storagePrice
	hn.Price = priceMap
}

// CalculateMetrics calculates the maximum number of search and write threads for warm nodes.
//
// The thread calculations are based on the total available CPUs across all warm nodes:
// - MaxNumberOfWriteThreads = AvailableCPUs (total CPUs across all warm nodes)
// - MaxNumberOfSearchThreads = (AvailableCPUs * 3/2) + node count
//
// This follows the same formula as hot nodes but applied to warm tier resources.
func (hn *WarmNodes) CalculateMetrics() {
	hn.MaxNumberOfWriteThreads = hn.AvailableCPUs
	hn.MaxNumberOfSearchThreads = ((hn.AvailableCPUs * 3) / 2) + hn.Count
}

// getInstancePrice returns the price for a given WarmNodes and instance unit.
//
// It returns an InstancePrice with the single instance cost, and the total cost, which is the single instance cost multiplied by the number of nodes.
//
// The single instance cost is calculated as follows:
// - The monthly cost is the price of the instance per hour, multiplied by 24 hours a day, and the number of days in a month.
// - The prepaid cost is the price of the instance for the given pricing option, which is either On Demand, or one of the reserved instance pricing options.
// - The total cost is the prepaid cost plus the monthly cost, multiplied by the number of nodes.
func (hn *WarmNodes) getInstancePrice(priceType string, iu price.InstanceUnit) *InstancePrice {
	yearly := strings.HasSuffix(priceType, "1")
	prepaidCost := iu.Price[priceType+"PC"].Price
	hourlyCost := iu.Price[priceType+"HC"].Price
	var monthlyCost float64
	switch {
	case priceType == OnDemand:
		monthlyCost = iu.Price[OnDemand].Price * 24 * price.DaysPerMonth
	case strings.HasPrefix(priceType, "AURI"):
		if yearly {
			monthlyCost = prepaidCost / 12
		} else {
			monthlyCost = prepaidCost / 36
		}
	case strings.HasPrefix(priceType, "NURI"):
		monthlyCost = hourlyCost * 24 * price.DaysPerMonth
	default:
		if yearly {
			monthlyCost = prepaidCost/12 + hourlyCost*24*price.DaysPerMonth
		} else {
			monthlyCost = prepaidCost/36 + hourlyCost*24*price.DaysPerMonth
		}
	}
	ip := &InstancePrice{
		SingleInstance: monthlyCost,
	}
	ip.TotalCost = ip.StorageCostPerInstance + (ip.SingleInstance * float64(hn.Count))
	return ip
}
