// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cache

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/instances"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
	"go.uber.org/zap"
)

// Isolated AWS partitions (aws-iso and aws-iso-b) use a different metered unit maps format
// than global regions:
//   - es-ultrawarm.json contains ALL instance types (hot + warm) with all pricing tiers
//   - Entries use hash-based keys and rateCode format: SKU.OFFER_TERM_CODE.RATE_ID
//   - Offer term codes identify the pricing type (OnDemand, 1yr/3yr RI)
//   - es-storage.json contains storage pricing in the standard format
//
// Offer term code mapping (constant across both partitions):
//
//	WCNHAN5AMX = OnDemand
//	6G6RXMSR5W = 1yr All Upfront
//	DGHNG4RYJT = 1yr Partial Upfront
//	T3XEW22SAH = 1yr No Upfront
//	UP3KH6A5GQ = 3yr All Upfront
//	CGP5P29WFS = 3yr Partial Upfront
//	KB3B5PG4UF = 3yr No Upfront
const (
	isoOfferOnDemand      = "WCNHAN5AMX"
	isoOffer1yrAllUpfront = "6G6RXMSR5W"
	isoOffer1yrPartial    = "DGHNG4RYJT"
	isoOffer1yrNoUpfront  = "T3XEW22SAH"
	isoOffer3yrAllUpfront = "UP3KH6A5GQ"
	isoOffer3yrPartial    = "CGP5P29WFS"
	isoOffer3yrNoUpfront  = "KB3B5PG4UF"
	isoRateHourly         = "6YS6EN2CT7"
	isoRateUpfront        = "2TG2D8R56U"
)

// isoOfferToPriceKey maps isolated partition offer term codes to internal price key prefixes.
var isoOfferToPriceKey = map[string]string{
	isoOfferOnDemand:      "OnDemand",
	isoOffer1yrAllUpfront: "AURI1",
	isoOffer1yrPartial:    "PURI1",
	isoOffer1yrNoUpfront:  "NURI1",
	isoOffer3yrAllUpfront: "AURI3",
	isoOffer3yrPartial:    "PURI3",
	isoOffer3yrNoUpfront:  "NURI3",
}

// IsSecretRegion returns true if the given region name is an AWS Secret (aws-iso-b) region.
func IsSecretRegion(regionName string) bool {
	return regions.IsSecretRegionDisplay(regionName)
}

// IsTopSecretRegion returns true if the given region name is an AWS Top Secret (aws-iso) region.
func IsTopSecretRegion(regionName string) bool {
	return regions.IsTopSecretRegionDisplay(regionName)
}

// IsIsolatedRegion returns true if the region is in any isolated partition (Secret or Top Secret).
func IsIsolatedRegion(regionName string) bool {
	return IsSecretRegion(regionName) || IsTopSecretRegion(regionName)
}

// processSecretPricing downloads and processes pricing data from the AWS Secret Region
// (aws-iso-b) partition, merging the results into the ProvisionedPrice regions map.
func (sl *ProvisionedPrice) processSecretPricing() error {
	return sl.processIsolatedPricingFromURLs("Secret", price.SecretInstanceUrl, price.SecretStorageUrl)
}

// processTopSecretPricing downloads and processes pricing data from the AWS Top Secret Region
// (aws-iso) partition, merging the results into the ProvisionedPrice regions map.
func (sl *ProvisionedPrice) processTopSecretPricing() error {
	return sl.processIsolatedPricingFromURLs("Top Secret", price.TopSecretInstanceUrl, price.TopSecretStorageUrl)
}

// processIsolatedPricingFromURLs fetches and processes pricing from an isolated AWS partition.
// The instanceUrl (es-ultrawarm.json) contains ALL instance types with all pricing tiers.
// partitionLabel is used for logging (e.g., "Secret", "Top Secret").
func (sl *ProvisionedPrice) processIsolatedPricingFromURLs(partitionLabel, instanceUrl, storageUrl string) error {
	// Process all instance pricing (hot + warm + RI) from the single endpoint
	if err := sl.processIsolatedInstanceData(instanceUrl); err != nil {
		return fmt.Errorf("failed to process %s region instance data: %w", partitionLabel, err)
	}

	// Process storage data
	if err := sl.processIsolatedStorageData(storageUrl); err != nil {
		zap.L().Warn("failed to process isolated region storage data",
			zap.String("partition", partitionLabel), zap.Error(err))
	}

	return nil
}

// processIsolatedInstanceData downloads and processes all instance pricing from an isolated
// partition's es-ultrawarm.json endpoint. This endpoint contains all instance types (hot + warm)
// with OnDemand and Reserved Instance pricing.
func (sl *ProvisionedPrice) processIsolatedInstanceData(url string) error {
	pricingJsonBytes, err := downloadJson(url)
	if err != nil {
		return err
	}
	rp, ok := toMap(pricingJsonBytes)
	if !ok {
		return fmt.Errorf("unexpected response format from isolated region instance pricing API")
	}

	for region, pricing := range rp {
		regPri, ok := sl.Regions[region]
		if !ok {
			regPri = price.ProvisionedRegion{}
		}
		if regPri.HotInstances == nil {
			regPri.HotInstances = make(map[string]price.InstanceUnit)
		}
		if regPri.WarmInstances == nil {
			regPri.WarmInstances = make(map[string]price.InstanceUnit)
		}

		regionMap, ok := toMap(pricing)
		if !ok {
			continue
		}

		for _, itemValue := range regionMap {
			itemMap, ok := toMap(itemValue)
			if !ok {
				continue
			}
			processIsolatedEntry(regPri.HotInstances, regPri.WarmInstances, itemMap)
		}

		sl.Regions[region] = regPri

		zap.L().Info("processed isolated region pricing",
			zap.String("region", region),
			zap.Int("hot_instances", len(regPri.HotInstances)),
			zap.Int("warm_instances", len(regPri.WarmInstances)))
	}
	return nil
}

// processIsolatedEntry processes a single entry from isolated partition pricing data,
// extracting the instance type, offer term, and pricing from the rateCode.
func processIsolatedEntry(hotInstances, warmInstances map[string]price.InstanceUnit, entry map[string]interface{}) {
	instanceType := toString(entry["Instance Type"])
	if instanceType == "" {
		return
	}

	rateCode := toString(entry["rateCode"])
	parts := strings.Split(rateCode, ".")
	if len(parts) < 3 {
		return
	}
	offerTermCode := parts[1]
	rateID := parts[2]

	priceKeyPrefix, known := isoOfferToPriceKey[offerTermCode]
	if !known {
		return
	}

	priceStr := toString(entry["price"])
	priceVal, _ := strconv.ParseFloat(priceStr, 64)

	isWarm := strings.HasPrefix(instanceType, "ultrawarm")

	targetMap := hotInstances
	if isWarm {
		targetMap = warmInstances
	}

	iu, exists := targetMap[instanceType]
	if !exists {
		iu = createIsolatedInstanceSkeleton(entry, isWarm)
	}

	if priceKeyPrefix == "OnDemand" {
		iu.Price["OnDemand"] = price.Unit{RateCode: rateCode, Price: priceVal}
	} else if rateID == isoRateHourly {
		key := priceKeyPrefix + "HC"
		iu.Price[key] = price.Unit{RateCode: rateCode, Price: priceVal}
	} else if rateID == isoRateUpfront {
		key := priceKeyPrefix + "PC"
		iu.Price[key] = price.Unit{RateCode: rateCode, Price: priceVal}
	}

	targetMap[instanceType] = iu
}

// createIsolatedInstanceSkeleton creates a new InstanceUnit from an isolated region pricing entry.
func createIsolatedInstanceSkeleton(entry map[string]interface{}, isWarm bool) price.InstanceUnit {
	instanceType := toString(entry["Instance Type"])
	cpu, _ := strconv.Atoi(toString(entry["vCPU"]))
	memory, _ := strconv.ParseFloat(toString(entry["Memory (GiB)"]), 64)
	jvmMemory := memory / 2
	if jvmMemory > 30.5 {
		jvmMemory = 30.5
	}

	iu := price.InstanceUnit{
		InstanceType: instanceType,
		CPU:          cpu,
		Memory:       memory,
		JVMMemory:    jvmMemory,
		Price:        make(map[string]price.Unit),
	}

	if isWarm {
		iu.Family = "UltraWarm"
		return iu
	}

	iu.Family = getInstanceFamily(instanceType)

	storageStr := toString(entry["Storage"])
	if storageStr == "EBS Only" || storageStr == "" {
		if ebsMap, found := instances.InstanceLimitsMap[instanceType]; found {
			iu.Storage.MinEBS = ebsMap.Minimum
			iu.Storage.MaxGP2 = ebsMap.MaximumGp2
			iu.Storage.MaxGP3 = ebsMap.MaximumGp3
		}
	} else {
		parts := strings.Fields(strings.TrimSpace(storageStr))
		if len(parts) >= 3 {
			n, _ := strconv.Atoi(parts[0])
			s, _ := strconv.Atoi(strings.ReplaceAll(parts[2], ",", ""))
			iu.Storage.Internal = n * s
		}
	}

	return iu
}

// processIsolatedStorageData downloads and processes storage pricing from an isolated partition.
func (sl *ProvisionedPrice) processIsolatedStorageData(url string) error {
	pricingJsonBytes, err := downloadJson(url)
	if err != nil {
		return err
	}
	rp, ok := toMap(pricingJsonBytes)
	if !ok {
		return nil
	}
	for region, pricing := range rp {
		regPri, ok := sl.Regions[region]
		if !ok {
			regPri = price.ProvisionedRegion{}
		}
		regionMap, ok := toMap(pricing)
		if !ok {
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
	return nil
}
