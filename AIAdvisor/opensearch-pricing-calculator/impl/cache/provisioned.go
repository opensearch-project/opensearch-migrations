// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cache

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/instances"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"go.uber.org/zap"
)

// toMap safely asserts an interface{} to map[string]interface{}.
// Returns nil, false if the assertion fails.
func toMap(v interface{}) (map[string]interface{}, bool) {
	m, ok := v.(map[string]interface{})
	return m, ok
}

// toString safely asserts an interface{} to string.
// Returns empty string if the assertion fails.
func toString(v interface{}) string {
	s, _ := v.(string)
	return s
}

const VCPUKey = "vCPU"
const InstanceType = "Instance Type"
const MemoryKey = "Memory (GiB)"
const PriceKey = "price"
const PriceRateCode = "rateCode"
const RiUpfrontRateCode = "riupfront:RateCode"
const RiUpfrontPrice = "riupfront:PricePerUnit"

var provisionedPriceCache *ProvisionedPrice

type ProvisionedPrice struct {
	Regions map[string]price.ProvisionedRegion `json:"regions"`
}

// GetAllRegions returns all regions as an array of strings.
func (sl *ProvisionedPrice) GetAllRegions() (regions []string) {
	for key := range sl.Regions {
		regions = append(regions, key)
	}
	return
}

// InvalidateCache Reloads provisioned price cache from json files, and updates the local copy.
//
// It processes warm data, hot data, reserved instances, and storage data for all regions.
// If any error occurs, it returns an InvalidationStatus with the error message.
// If no error occurs, it returns an InvalidationStatus with a success message, the timestamp of the update, and it writes the output to a local file named "priceCache.json".
func (sl *ProvisionedPrice) InvalidateCache() (response InvalidationStatus) {
	sl.Regions = make(map[string]price.ProvisionedRegion)
	err := sl.processWarmData()
	if err != nil {
		return InvalidationStatus{
			Message: err.Error(),
		}
	}

	err = sl.processHotData1()
	if err != nil {
		return InvalidationStatus{
			Message: err.Error(),
		}
	}
	err = sl.processReservedInstances()
	if err != nil {
		return InvalidationStatus{
			Message: err.Error(),
		}
	}
	err = sl.processStorageData()
	if err != nil {
		return InvalidationStatus{
			Message: err.Error(),
		}
	}
	// Process China region pricing (separate API, CNY currency)
	err = sl.processChinaPricing()
	if err != nil {
		zap.L().Warn("failed to process China pricing, continuing without China regions", zap.Error(err))
	}
	// Process Secret region pricing (aws-iso-b partition, USD currency)
	err = sl.processSecretPricing()
	if err != nil {
		zap.L().Warn("failed to process Secret region pricing, continuing without Secret regions", zap.Error(err))
	}
	// Process Top Secret region pricing (aws-iso partition, USD currency)
	err = sl.processTopSecretPricing()
	if err != nil {
		zap.L().Warn("failed to process Top Secret region pricing, continuing without Top Secret regions", zap.Error(err))
	}
	//write the output to file
	file, err := json.MarshalIndent(sl.Regions, " ", "  ")
	if err != nil {
		return InvalidationStatus{
			Message: fmt.Sprintf("Error marshaling cache data: %s", err.Error()),
		}
	}
	err = os.WriteFile("./priceCache.json", file, 0644)
	if err != nil {
		return InvalidationStatus{
			Message: fmt.Sprintf("Error writing cache file: %s", err.Error()),
		}
	}
	return InvalidationStatus{
		Message:     fmt.Sprintf("New provisioned price for %d regions updated", len(sl.Regions)),
		UpdatedTime: time.Now(),
	}
}

// InvalidateCacheForRegion Reloads provisioned price cache for a specific region only.
//
// This method is useful for debugging pricing issues in a single region without
// affecting the cached prices for other regions. It:
// 1. Loads existing cache from file (preserves other regions)
// 2. Downloads and processes pricing data for the SPECIFIED region only
// 3. Updates ONLY the specified region in the cache
// 4. Writes the updated cache back to file
//
// Returns an error if the region is not found in the pricing data.
func (sl *ProvisionedPrice) InvalidateCacheForRegion(regionName string) (response InvalidationStatus) {
	zap.L().Info("region-specific cache invalidation started",
		zap.String("region", regionName),
		zap.String("note", "other regions will be preserved from existing cache"))

	// Load existing cache to preserve other regions
	sl.LoadFromLocalFile()
	existingRegions := make(map[string]price.ProvisionedRegion)
	for k, v := range sl.Regions {
		existingRegions[k] = v
	}

	// Isolated partitions (Secret aws-iso-b, Top Secret aws-iso) use separate pricing APIs
	if IsIsolatedRegion(regionName) {
		partitionLabel := "Secret"
		var processFunc func() error
		if IsSecretRegion(regionName) {
			processFunc = func() error {
				tempCache := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
				if err := tempCache.processSecretPricing(); err != nil {
					return err
				}
				newRegionData, found := tempCache.Regions[regionName]
				if !found {
					return fmt.Errorf("region '%s' not found in pricing data", regionName)
				}
				sl.Regions = existingRegions
				sl.Regions[regionName] = newRegionData
				return nil
			}
		} else {
			partitionLabel = "Top Secret"
			processFunc = func() error {
				tempCache := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
				if err := tempCache.processTopSecretPricing(); err != nil {
					return err
				}
				newRegionData, found := tempCache.Regions[regionName]
				if !found {
					return fmt.Errorf("region '%s' not found in pricing data", regionName)
				}
				sl.Regions = existingRegions
				sl.Regions[regionName] = newRegionData
				return nil
			}
		}

		zap.L().Info("using isolated partition pricing API", zap.String("partition", partitionLabel), zap.String("region", regionName))
		if err := processFunc(); err != nil {
			return InvalidationStatus{
				Message: fmt.Sprintf("Error processing %s region pricing: %s", partitionLabel, err.Error()),
			}
		}

		zap.L().Info("cache update complete",
			zap.String("updated_region", regionName),
			zap.Int("preserved_regions", len(existingRegions)-1))

		file, err := json.MarshalIndent(sl.Regions, " ", "  ")
		if err != nil {
			return InvalidationStatus{
				Message: fmt.Sprintf("Error marshaling cache data: %s", err.Error()),
			}
		}
		err = os.WriteFile("./priceCache.json", file, 0644)
		if err != nil {
			return InvalidationStatus{
				Message: fmt.Sprintf("Error writing cache file: %s", err.Error()),
			}
		}
		return InvalidationStatus{
			Message:     fmt.Sprintf("Pricing updated for %s region: %s", partitionLabel, regionName),
			UpdatedTime: time.Now(),
		}
	}

	// China regions use a separate pricing API
	if IsChinaRegion(regionName) {
		zap.L().Info("using China Bulk Pricing API for region", zap.String("region", regionName))
		tempCache := &ProvisionedPrice{Regions: make(map[string]price.ProvisionedRegion)}
		err := tempCache.processChinaPricing()
		if err != nil {
			return InvalidationStatus{
				Message: fmt.Sprintf("Error processing China pricing: %s", err.Error()),
			}
		}
		newRegionData, found := tempCache.Regions[regionName]
		if !found {
			return InvalidationStatus{
				Message: fmt.Sprintf("Region '%s' not found in China pricing data", regionName),
			}
		}
		sl.Regions = existingRegions
		sl.Regions[regionName] = newRegionData

		zap.L().Info("cache update complete",
			zap.String("updated_region", regionName),
			zap.Int("preserved_regions", len(existingRegions)-1))

		file, err := json.MarshalIndent(sl.Regions, " ", "  ")
		if err != nil {
			return InvalidationStatus{
				Message: fmt.Sprintf("Error marshaling cache data: %s", err.Error()),
			}
		}
		err = os.WriteFile("./priceCache.json", file, 0644)
		if err != nil {
			return InvalidationStatus{
				Message: fmt.Sprintf("Error writing cache file: %s", err.Error()),
			}
		}
		return InvalidationStatus{
			Message:     fmt.Sprintf("Pricing updated for China region: %s", regionName),
			UpdatedTime: time.Now(),
		}
	}

	// Download and process pricing data for all regions initially
	// (needed because AWS APIs return data for all regions)
	zap.L().Info("downloading pricing data (AWS APIs return all regions, will extract target only)",
		zap.String("target_region", regionName))
	tempRegions := make(map[string]price.ProvisionedRegion)
	sl.Regions = tempRegions

	err := sl.processWarmData()
	if err != nil {
		return InvalidationStatus{
			Message: fmt.Sprintf("Error processing warm data: %s", err.Error()),
		}
	}

	err = sl.processHotData1()
	if err != nil {
		return InvalidationStatus{
			Message: fmt.Sprintf("Error processing hot data: %s", err.Error()),
		}
	}

	err = sl.processStorageData()
	if err != nil {
		return InvalidationStatus{
			Message: fmt.Sprintf("Error processing storage data: %s", err.Error()),
		}
	}

	// Check if the requested region exists before processing expensive RI data
	_, found := tempRegions[regionName]
	if !found {
		return InvalidationStatus{
			Message: fmt.Sprintf("Region '%s' not found in pricing data. Available regions: %d", regionName, len(tempRegions)),
		}
	}

	zap.L().Info("extracted pricing data for target region",
		zap.String("region", regionName),
		zap.Int("discarded_regions", len(tempRegions)-1))

	// NOW filter to only the requested region before processing reserved instances
	// This prevents downloading RI pricing for all regions
	filteredRegions := make(map[string]price.ProvisionedRegion)
	filteredRegions[regionName] = tempRegions[regionName]
	sl.Regions = filteredRegions

	zap.L().Info("processing reserved instances for region", zap.String("region", regionName))
	err = sl.processReservedInstances()
	if err != nil {
		return InvalidationStatus{
			Message: fmt.Sprintf("Error processing reserved instances: %s", err.Error()),
		}
	}

	// Get the fully processed region data
	newRegionData := sl.Regions[regionName]

	// Update only the specified region, preserve others
	sl.Regions = existingRegions
	sl.Regions[regionName] = newRegionData

	zap.L().Info("cache update complete",
		zap.String("updated_region", regionName),
		zap.Int("preserved_regions", len(existingRegions)-1))

	// Write updated cache to file
	file, err := json.MarshalIndent(sl.Regions, " ", "  ")
	if err != nil {
		return InvalidationStatus{
			Message: fmt.Sprintf("Error marshaling cache data: %s", err.Error()),
		}
	}
	err = os.WriteFile("./priceCache.json", file, 0644)
	if err != nil {
		return InvalidationStatus{
			Message: fmt.Sprintf("Error writing cache file: %s", err.Error()),
		}
	}

	return InvalidationStatus{
		Message:     fmt.Sprintf("Pricing updated for region: %s", regionName),
		UpdatedTime: time.Now(),
	}
}

// processHotData1 Processes the reserved instances pricing.
//
// It reads the json from the given urls, processes it and updates the HotInstances field in the ProvisionedPrice struct.
// If any error occurs, it returns the error.
//
// Price Selection Logic: AWS pricing API contains multiple entries per instance type with keys ending in " 0".
// To ensure deterministic price selection (consistent across cache refreshes), we:
// 1. Collect all keys ending with " 0"
// 2. Sort them alphabetically
// 3. Process in sorted order
// This prevents random map iteration order from causing price inconsistencies.
func (sl *ProvisionedPrice) processHotData1() (err error) {
	esjson, err := downloadJson(price.EsOnDemandUrl)
	if err != nil {
		return
	}
	rp, ok := toMap(esjson)
	if !ok {
		return fmt.Errorf("unexpected response format from on-demand pricing API")
	}
	for region, pricing := range rp {
		regPri, ok := sl.Regions[region]
		if !ok {
			regPri = price.ProvisionedRegion{}
		}
		if regPri.HotInstances == nil {
			regPri.HotInstances = make(map[string]price.InstanceUnit)
		}
		regionMap, ok := toMap(pricing)
		if !ok {
			zap.L().Warn("skipping region with unexpected data format", zap.String("region", region))
			continue
		}

		// Collect all keys ending with " 0" for deterministic processing
		var keys []string
		for itemKey := range regionMap {
			if strings.HasSuffix(itemKey, " 0") {
				keys = append(keys, itemKey)
			}
		}

		// Sort keys alphabetically for consistent order across cache refreshes
		sort.Strings(keys)

		zap.L().Debug("processing OnDemand pricing for region",
			zap.String("region", region),
			zap.Int("entries", len(keys)))

		// Track instance types to detect duplicates
		instanceTypesSeen := make(map[string]int)

		// Process entries in sorted order
		for _, key := range keys {
			itemMap, ok := toMap(regionMap[key])
			if !ok {
				continue
			}

			// Extract instance type for logging
			if instanceType, ok := itemMap[InstanceType].(string); ok && instanceType != "" {
				instanceTypesSeen[instanceType]++
				if instanceTypesSeen[instanceType] > 1 {
					zap.L().Warn("multiple pricing entries for instance type",
						zap.String("instance_type", instanceType),
						zap.String("region", region),
						zap.Int("entry_number", instanceTypesSeen[instanceType]),
						zap.String("key", key))
				}
			}

			updateHotInstance1(regPri.HotInstances, itemMap, "OnDemand")
		}

		// Log summary of duplicates
		duplicateCount := 0
		for _, count := range instanceTypesSeen {
			if count > 1 {
				duplicateCount++
			}
		}
		if duplicateCount > 0 {
			zap.L().Warn("region has instance types with multiple pricing entries",
				zap.String("region", region),
				zap.Int("duplicate_count", duplicateCount))
		}

		sl.Regions[region] = regPri
	}
	return
}

// processReservedInstances processes the reserved instances pricing.
//
// It reads the json from the given urls, processes it and updates the HotInstances field in the ProvisionedPrice struct.
// If any error occurs, it returns the error.
//
// The urls are constructed by filling in the placeholders in the EsRiBaseUrl string with the pricing term, pricing option, region and instance family.
// If the url returns an error starting with "invalid character <", it is skipped.
func (sl *ProvisionedPrice) processReservedInstances() (err error) {
	for region := range sl.Regions {
		for _, pricingTerm := range price.PricingTerms {
			for _, pricingOption := range price.PricingOptions {
				for _, instanceFamily := range price.InstanceFamilies {
					fullUrl := fmt.Sprintf(price.EsRiBaseUrl, pricingTerm, pricingOption, region, instanceFamily)
					esjson, err := downloadJson(fullUrl)
					if esjson == nil || (err != nil && strings.HasPrefix(err.Error(), "invalid character '<'")) {
						zap.L().Debug("skipping RI URL", zap.String("url", fullUrl))
						continue
					}
					zap.L().Debug("processing RI URL", zap.String("url", fullUrl))

					rp, ok := toMap(esjson)
					if !ok {
						zap.L().Warn("unexpected response format from RI pricing API", zap.String("url", fullUrl))
						continue
					}
					for region, pricing := range rp {
						regPri, ok := sl.Regions[region]
						if !ok {
							regPri = price.ProvisionedRegion{}
						}
						if regPri.HotInstances == nil {
							regPri.HotInstances = make(map[string]price.InstanceUnit)
						}
						key1 := getPriceKey1(pricingTerm, pricingOption)
						regionMap, ok := toMap(pricing)
						if !ok {
							continue
						}
						for _, itemValue := range regionMap {
							if itemMap, ok := toMap(itemValue); ok {
								updateHotInstance1(regPri.HotInstances, itemMap, key1)
							}
						}
						sl.Regions[region] = regPri
					}
				}
			}
		}
	}
	return
}

// processWarmData Processes the warm data.
// It reads the json from the given url, processes it and updates the WarmInstances field in the ProvisionedPrice struct.
// If any error occurs, it returns the error.
func (sl *ProvisionedPrice) processWarmData() (err error) {
	pricingJsonBytes, err := downloadJson(price.UWUrl)
	if err != nil {
		return
	}
	rp, ok := toMap(pricingJsonBytes)
	if !ok {
		return fmt.Errorf("unexpected response format from warm pricing API")
	}
	for region, pricing := range rp {
		regPri, ok := sl.Regions[region]
		if !ok {
			regPri = price.ProvisionedRegion{}
		}
		if regPri.WarmInstances == nil {
			regPri.WarmInstances = make(map[string]price.InstanceUnit)
		}
		regionMap, ok := toMap(pricing)
		if !ok {
			zap.L().Warn("skipping region with unexpected data format", zap.String("region", region))
			continue
		}
		for itemKey, itemValue := range regionMap {
			itemMap, ok := toMap(itemValue)
			if !ok {
				continue
			}
			switch itemKey {
			case "Amazon OpenSearch Service Volume Managed-Storage per GigaBytes":
				regPri.Storage.ManagedStorage.RateCode, regPri.Storage.ManagedStorage.Price = getRateCodeAndPrice(itemMap)
			default:
				if strings.HasPrefix(itemKey, "ultrawarm") {
					updateWarmInstance(regPri.WarmInstances, itemMap)
				}
			}
		}
		sl.Regions[region] = regPri
	}
	return
}

// processStorageData Processes the storage data.
// It reads the json from the given url, processes it and updates the Storage field in the ProvisionedPrice struct.
// If any error occurs, it returns the error.
func (sl *ProvisionedPrice) processStorageData() (err error) {
	pricingJsonBytes, err := downloadJson(price.StorageUrl)
	if err != nil {
		return
	}
	rp, ok := toMap(pricingJsonBytes)
	if !ok {
		return fmt.Errorf("unexpected response format from storage pricing API")
	}
	for region, pricing := range rp {
		regPri, ok := sl.Regions[region]
		if !ok {
			regPri = price.ProvisionedRegion{}
		}
		regionMap, ok := toMap(pricing)
		if !ok {
			zap.L().Warn("skipping region with unexpected data format", zap.String("region", region))
			continue
		}
		for itemKey, itemValue := range regionMap {
			itemMap, ok := toMap(itemValue)
			if !ok {
				continue
			}
			switch itemKey {
			case "GP2":
				regPri.Storage.Gp2.RateCode, regPri.Storage.Gp2.Price = getRateCodeAndPrice(itemMap)
			case "GP3":
				regPri.Storage.Gp3.RateCode, regPri.Storage.Gp3.Price = getRateCodeAndPrice(itemMap)
			case "GP3 PIOPS-Storage":
				regPri.Storage.Gp3ProvisionedIOPS.RateCode, regPri.Storage.Gp3ProvisionedIOPS.Price = getRateCodeAndPrice(itemMap)
			case "GP3 ThroughPut-Storage":
				regPri.Storage.Gp3Provisioned.RateCode, regPri.Storage.Gp3Provisioned.Price = getRateCodeAndPrice(itemMap)
			case "PIOPS":
				regPri.Storage.Gp2ProvisionedIOPS.RateCode, regPri.Storage.Gp2ProvisionedIOPS.Price = getRateCodeAndPrice(itemMap)
			case "PIOPS Storage":
				regPri.Storage.Gp2Provisioned.RateCode, regPri.Storage.Gp2Provisioned.Price = getRateCodeAndPrice(itemMap)
			case "Magnetic":
				regPri.Storage.Magnetic.RateCode, regPri.Storage.Magnetic.Price = getRateCodeAndPrice(itemMap)
			case "Managed Storage per GigaBytes":
				regPri.Storage.ManagedStorage.RateCode, regPri.Storage.ManagedStorage.Price = getRateCodeAndPrice(itemMap)
			}
		}
		sl.Regions[region] = regPri
	}
	return
}

// LoadFromLocalFile loads the provisioned price cache from a local file.
//
// The file is expected to be named "priceCache.json" and should be located in the
// same directory as the binary.
//
// If the file does not exist, it will be populated by calling InvalidateCache.
func (sl *ProvisionedPrice) LoadFromLocalFile() {
	if _, err := os.Stat("./priceCache.json"); err == nil {
		data, err := os.ReadFile("./priceCache.json")
		if err != nil {
			zap.L().Warn("failed to read price cache file, will re-download", zap.Error(err))
			sl.InvalidateCache()
			return
		}
		if err := json.Unmarshal(data, &sl.Regions); err != nil {
			zap.L().Warn("failed to parse price cache file, will re-download", zap.Error(err))
			sl.InvalidateCache()
		}
	} else if errors.Is(err, os.ErrNotExist) {
		sl.InvalidateCache()
	}
}

// updateWarmInstance updates the given instances map with the given itemValue map.
//
// It first checks if the instanceType is empty, and if so, returns without doing anything.
// It then checks if the instanceType is present in the instances map, and if not, creates a new InstanceUnit with the given itemValue map and adds it to the instances map.
// It then iterates over the itemValue map and updates the CPU, Price, Memory, JVM Memory, and RateCode of the instance unit if the corresponding key is present.
// Finally, it updates the instances map with the updated instance unit.
func updateWarmInstance(instances map[string]price.InstanceUnit, itemValue map[string]interface{}) {
	instanceType := getWarmInstanceType(itemValue)
	if instanceType == "" {
		//if the instance type is not found, skip it
		return
	}
	iu, found := instances[instanceType]
	if !found {
		iu = price.InstanceUnit{}
		iu.Price = make(map[string]price.Unit)
	}
	iu.Family = "UltraWarm"
	for ik, iv := range itemValue {
		s := toString(iv)
		switch ik {
		case "Instance Type":
			iu.InstanceType = s
		case VCPUKey:
			iu.CPU, _ = strconv.Atoi(s)
		case "InternalStorage":
			intb, err := strconv.ParseFloat(s, 64)
			if err == nil {
				iu.Storage.Internal = int(intb * 1024)
			}
		case PriceKey:
			u, found := iu.Price["OnDemand"]
			if !found {
				u = price.Unit{}
			}
			u.Price, _ = strconv.ParseFloat(s, 64)
			iu.Price["OnDemand"] = u
		case MemoryKey:
			iu.Memory, _ = strconv.ParseFloat(s, 64)
			jvmMemory := iu.Memory / 2
			if jvmMemory > 30.5 {
				jvmMemory = 30.5
			}
			iu.JVMMemory = jvmMemory
		case PriceRateCode:
			u, found := iu.Price["OnDemand"]
			if !found {
				u = price.Unit{}
			}
			u.RateCode = s
			iu.Price["OnDemand"] = u
		}
	}
	instances[instanceType] = iu
}

// getWarmInstanceType returns the instance type for the given itemValue map.
//
// It iterates over the itemValue map and returns the value of the key "Instance Type".
// If the key is not found, it returns an empty string.
func getWarmInstanceType(itemValue map[string]interface{}) (instanceType string) {
	for ik, iv := range itemValue {
		switch ik {
		case "Instance Type":
			return toString(iv)
		}
	}
	return
}

// updateHotInstance1 updates the given instances map with the given itemValue map and itemkey.
//
// It checks if the instanceType is empty or starts with "ultrawarm", and if so, returns without doing anything.
// It then checks if the instanceType is present in the instances map, and if not, creates a new InstanceUnit with
// the given itemValue map and adds it to the instances map.
// It then iterates over the itemValue map and updates the CPU, Price, Memory, JVM Memory, and RateCode of the
// instance unit if the corresponding key is present.
// If the priceBaseKey is not "OnDemand", it also updates the RiUpfrontRateCode and RiUpfrontPrice of the instance unit.
// Finally, it updates the instances map with the updated instance unit.
func updateHotInstance1(instances map[string]price.InstanceUnit, itemValue map[string]interface{}, priceBaseKey string) {
	instanceType, _ := itemValue[InstanceType].(string)
	if instanceType == "" || strings.HasPrefix(instanceType, "ultrawarm") {
		//no instance found
		return
	}
	iu, found := instances[instanceType]
	if !found {
		iu = createNewOnDemandHotInstanceUnit(itemValue)
	}
	priceKey := priceBaseKey
	var prepaidCostKey string
	if priceBaseKey != "OnDemand" {
		priceKey = priceBaseKey + "HC"
		prepaidCostKey = priceBaseKey + "PC"
	}
	for ik, iv := range itemValue {
		s := toString(iv)
		switch ik {
		case VCPUKey:
			if !found {
				iu.CPU, _ = strconv.Atoi(s)
			}
		case PriceKey:
			cost, _ := strconv.ParseFloat(s, 64)
			u, ok := iu.Price[priceKey]
			if !ok {
				u = price.Unit{}
			}
			u.Price = cost
			iu.Price[priceKey] = u
		case MemoryKey:
			if !found {
				iu.Memory, _ = strconv.ParseFloat(s, 64)
				jvmMemory := iu.Memory / 2
				if jvmMemory > 30.5 {
					jvmMemory = 30.5
				}
				iu.JVMMemory = jvmMemory
			}
		case PriceRateCode:
			u, ok := iu.Price[priceKey]
			if !ok {
				u = price.Unit{}
			}
			u.RateCode = s
			iu.Price[priceKey] = u
		case RiUpfrontRateCode:
			u, ok := iu.Price[prepaidCostKey]
			if !ok {
				u = price.Unit{}
			}
			u.RateCode = s
			iu.Price[prepaidCostKey] = u

		case RiUpfrontPrice:
			cost, _ := strconv.ParseFloat(s, 64)
			u, ok := iu.Price[prepaidCostKey]
			if !ok {
				u = price.Unit{}
			}
			u.Price = cost
			iu.Price[prepaidCostKey] = u
		}
	}
	instances[instanceType] = iu
}

// createNewOnDemandHotInstanceUnit creates a new instance unit given a key and a value map.
//
// The value map should contain the following keys:
//   - InstanceType: the instance type of the instance unit
//   - Storage: the storage of the instance unit, which can be "EBS Only" or a string in the format "X Y Z",
//     where X is the number of volumes, Y is the volume type, and Z is the size of the volume in GB.
//
// If the instance type is empty or starts with "ultrawarm", the function returns an empty instance unit.
// If the storage is "EBS Only", the function looks up the minimum and maximum EBS storage for the instance type in the InstanceLimitsMap and sets the instance unit's Storage field accordingly.
// Otherwise, the function calculates the storage based on the given string and sets the instance unit's Storage field accordingly.
// Finally, the function returns the newly created instance unit.
func createNewOnDemandHotInstanceUnit(value map[string]interface{}) (iu price.InstanceUnit) {
	instanceType := toString(value[InstanceType])
	//if the instance type is not empty, skip it
	if instanceType == "" || strings.HasPrefix(instanceType, "ultrawarm") {
		return
	}

	iu.Price = make(map[string]price.Unit)
	iu.InstanceType = instanceType

	// Use the new getInstanceFamily function with regex
	iu.Family = getInstanceFamily(iu.InstanceType)
	//check the storage from value map
	v1, found := value["Storage"]
	if found {
		storageType := toString(v1)
		if storageType != "EBS Only" && storageType != "" {
			y := strings.TrimSpace(storageType)
			st1 := strings.Split(y, " ")
			n, _ := strconv.Atoi(st1[0])
			s, _ := strconv.Atoi(strings.ReplaceAll(st1[2], ",", ""))
			iu.Storage.Internal = n * s
		} else {
			ebsMap, found := instances.InstanceLimitsMap[iu.InstanceType]
			if found {
				iu.Storage.MinEBS = ebsMap.Minimum
				iu.Storage.MaxGP2 = ebsMap.MaximumGp2
				iu.Storage.MaxGP3 = ebsMap.MaximumGp3
			}
		}
	} else {
		// Fallback: If AWS doesn't provide Storage field, check InstanceLimitsMap
		// This handles newer instance types (like r8g) where AWS pricing API may not include Storage
		ebsMap, found := instances.InstanceLimitsMap[iu.InstanceType]
		if found {
			iu.Storage.MinEBS = ebsMap.Minimum
			iu.Storage.MaxGP2 = ebsMap.MaximumGp2
			iu.Storage.MaxGP3 = ebsMap.MaximumGp3
		}
	}

	return iu
}

// getInstanceFamily takes an instance name string and returns the instance family as a string.
//
// The function uses a map of regular expression patterns to match against the given instance name, and returns the corresponding instance family string.
// If the instance name does not match any pattern, the function returns an empty string.
func getInstanceFamily(instanceName string) string {
	for pattern, family := range instances.FamilyPatterns {
		matched, _ := regexp.MatchString(pattern, instanceName)
		if matched {
			return family
		}
	}
	return ""
}

// getPriceKey1 returns the price key suffix based on the pricing option and term.
//
// The input priceOption should be one of "Partial Upfront", "All Upfront", or "No Upfront".
// The input priceTerm should be one of "1 year" or "3 years".
//
// The function returns the price key suffix in the format "XXUR[1|3]" where XX is one of
// "P", "A", or "N" depending on the pricing option, and the number is either 1 or 3 depending
// on the price term.
func getPriceKey1(priceTerm string, priceOption string) (priceKey string) {
	switch priceOption {
	case "Partial Upfront":
		priceKey = getPriceKeySuffix1("PURI", priceTerm)
	case "All Upfront":
		priceKey = getPriceKeySuffix1("AURI", priceTerm)
	case "No Upfront":
		priceKey = getPriceKeySuffix1("NURI", priceTerm)
	}
	return
}

// getPriceKeySuffix1 returns the price key with the correct suffix based on the pricing option and term.
//
// The function takes the base price key and the price term, and returns the price key with the correct suffix.
//
// The suffix is determined by the pricing term, in the following order:
// - The pricing term is either "1 year" or "3 years".
//
// The function returns the price key with the correct suffix in the format
// "X[1|3]" where X is the base price key and the number is either 1 or 3 depending on the price term.
func getPriceKeySuffix1(base string, priceTerm string) (priceKey string) {
	priceKey = base
	if priceTerm == "1 year" {
		priceKey += "1"
	} else {
		priceKey += "3"
	}
	return
}

// GetProvisionedPrice returns the provisioned price cache.
//
// The function is thread-safe and will not return nil. It will return the same instance
// of ProvisionedPrice on every call, unless the cache is invalidated, in which case
// it will return a new instance of ProvisionedPrice.
//
// The provisioned price cache is a map of region names to ProvisionedRegion objects.
// The cache is populated by the LoadFromLocalFile method, which reads the cache from
// a local file. If the file does not exist, it will be populated by calling InvalidateCache.
//
// The cache is used by the provisioned pricing handler to return the provisioned
// pricing for all regions.
func GetProvisionedPrice() *ProvisionedPrice {
	lock.Lock()
	defer lock.Unlock()
	if provisionedPriceCache == nil {
		provisionedPriceCache = &ProvisionedPrice{}
		provisionedPriceCache.Regions = make(map[string]price.ProvisionedRegion)
		provisionedPriceCache.LoadFromLocalFile()
	}
	return provisionedPriceCache
}

// GetRegionProvisionedPrice returns the provisioned pricing for the given region.
//
// The function returns the ProvisionedRegion object for the given region, or an error if the region is not found.
//
// The function is thread-safe and will not return nil. It will return the same instance
// of ProvisionedRegion on every call, unless the cache is invalidated, in which case
// it will return a new instance of ProvisionedRegion.
func GetRegionProvisionedPrice(region string) (price.ProvisionedRegion, error) {
	pp, ok := GetProvisionedPrice().Regions[region]
	if ok {
		return pp, nil
	}
	return pp, errors.New("region not found")
}
