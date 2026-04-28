// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cache

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/instances"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"go.uber.org/zap"
)

// chinaBulkPricing represents the top-level structure of the AWS China Bulk Pricing API response.
type chinaBulkPricing struct {
	Products map[string]chinaProduct `json:"products"`
	Terms    chinaTerms              `json:"terms"`
}

type chinaProduct struct {
	SKU           string                 `json:"sku"`
	ProductFamily string                 `json:"productFamily"`
	Attributes    chinaProductAttributes `json:"attributes"`
}

type chinaProductAttributes struct {
	Location       string `json:"location"`
	InstanceType   string `json:"instanceType"`
	VCPU           string `json:"vcpu"`
	MemoryGiB      string `json:"memoryGib"`
	Storage        string `json:"storage"`
	InstanceFamily string `json:"instanceFamily"`
	StorageMedia   string `json:"storageMedia"`
	RegionCode     string `json:"regionCode"`
}

type chinaTerms struct {
	OnDemand map[string]map[string]chinaOffer `json:"OnDemand"`
	Reserved map[string]map[string]chinaOffer `json:"Reserved"`
}

type chinaOffer struct {
	OfferTermCode   string                   `json:"offerTermCode"`
	SKU             string                   `json:"sku"`
	PriceDimensions map[string]chinaPriceDim `json:"priceDimensions"`
	TermAttributes  chinaTermAttributes      `json:"termAttributes"`
}

type chinaPriceDim struct {
	RateCode     string            `json:"rateCode"`
	Description  string            `json:"description"`
	Unit         string            `json:"unit"`
	PricePerUnit map[string]string `json:"pricePerUnit"`
}

type chinaTermAttributes struct {
	LeaseContractLength string `json:"LeaseContractLength"`
	PurchaseOption      string `json:"PurchaseOption"`
}

// ChinaRegions contains the display names for AWS China regions.
var ChinaRegions = []string{"China (Beijing)", "China (Ningxia)"}

// IsChinaRegion returns true if the given region name is an AWS China region.
func IsChinaRegion(regionName string) bool {
	for _, r := range ChinaRegions {
		if r == regionName {
			return true
		}
	}
	return false
}

// processChinaPricing downloads and processes pricing data from the AWS China
// Bulk Pricing API, merging the results into the ProvisionedPrice regions map.
func (sl *ProvisionedPrice) processChinaPricing() error {
	return sl.processChinaPricingFromURL(price.ChinaPricingUrl)
}

// processChinaPricingFromURL fetches from a given URL and processes the response.
// Separated from processChinaPricing to allow test injection.
func (sl *ProvisionedPrice) processChinaPricingFromURL(url string) error {
	bulk, err := downloadChinaBulkPricing(url)
	if err != nil {
		return fmt.Errorf("failed to download China pricing: %w", err)
	}

	// Build region data from products + terms
	regionData := make(map[string]*regionBuilder)

	// Pass 1: process products to create instance/storage skeletons keyed by SKU
	skuMap := make(map[string]chinaProduct) // SKU -> product for term lookups
	for sku, prod := range bulk.Products {
		skuMap[sku] = prod
		location := prod.Attributes.Location
		rb := getOrCreateRegionBuilder(regionData, location)

		switch prod.ProductFamily {
		case "Amazon OpenSearch Service Instance":
			rb.addInstanceSkeleton(prod)
		case "Amazon OpenSearch Service Volume":
			// Storage pricing handled in terms pass
		}
	}

	// Pass 2: process OnDemand terms
	for sku, offers := range bulk.Terms.OnDemand {
		prod, ok := skuMap[sku]
		if !ok {
			continue
		}
		location := prod.Attributes.Location
		rb := getOrCreateRegionBuilder(regionData, location)

		for _, offer := range offers {
			for _, dim := range offer.PriceDimensions {
				cnyPrice := parseCNY(dim.PricePerUnit)

				switch prod.ProductFamily {
				case "Amazon OpenSearch Service Instance":
					rb.setOnDemandPrice(prod.Attributes.InstanceType, dim.RateCode, cnyPrice)
				case "Amazon OpenSearch Service Volume":
					rb.setStoragePrice(prod.Attributes.StorageMedia, dim.RateCode, cnyPrice)
				}
			}
		}
	}

	// Pass 3: process Reserved terms
	for sku, offers := range bulk.Terms.Reserved {
		prod, ok := skuMap[sku]
		if !ok {
			continue
		}
		if prod.ProductFamily != "Amazon OpenSearch Service Instance" {
			continue
		}
		location := prod.Attributes.Location
		rb := getOrCreateRegionBuilder(regionData, location)

		for _, offer := range offers {
			priceKey := chinaPriceKey(offer.TermAttributes)
			if priceKey == "" {
				continue
			}
			for _, dim := range offer.PriceDimensions {
				cnyPrice := parseCNY(dim.PricePerUnit)
				instanceType := prod.Attributes.InstanceType

				if strings.HasPrefix(instanceType, "ultrawarm") {
					// UltraWarm doesn't have RI pricing
					continue
				}

				switch dim.Unit {
				case "Hrs":
					// Hourly cost
					rb.setReservedHourlyPrice(instanceType, priceKey, dim.RateCode, cnyPrice)
				case "Quantity":
					// Upfront fee
					rb.setReservedUpfrontPrice(instanceType, priceKey, dim.RateCode, cnyPrice)
				}
			}
		}
	}

	// Merge into ProvisionedPrice regions
	for location, rb := range regionData {
		region := rb.build()
		sl.Regions[location] = region
		zap.L().Info("processed China region pricing",
			zap.String("region", location),
			zap.Int("hot_instances", len(region.HotInstances)),
			zap.Int("warm_instances", len(region.WarmInstances)),
			zap.String("currency", region.Currency))
	}

	return nil
}

// regionBuilder accumulates pricing data for a single China region.
type regionBuilder struct {
	hotInstances  map[string]price.InstanceUnit
	warmInstances map[string]price.InstanceUnit
	storage       price.Storage
}

func getOrCreateRegionBuilder(m map[string]*regionBuilder, location string) *regionBuilder {
	rb, ok := m[location]
	if !ok {
		rb = &regionBuilder{
			hotInstances:  make(map[string]price.InstanceUnit),
			warmInstances: make(map[string]price.InstanceUnit),
		}
		m[location] = rb
	}
	return rb
}

func (rb *regionBuilder) addInstanceSkeleton(prod chinaProduct) {
	attrs := prod.Attributes
	instanceType := attrs.InstanceType
	if instanceType == "" {
		return
	}

	cpu, _ := strconv.Atoi(attrs.VCPU)
	memory, _ := strconv.ParseFloat(attrs.MemoryGiB, 64)
	jvmMemory := memory / 2
	if jvmMemory > 30.5 {
		jvmMemory = 30.5
	}

	if strings.HasPrefix(instanceType, "ultrawarm") {
		iu := price.InstanceUnit{
			InstanceType: instanceType,
			Family:       "UltraWarm",
			CPU:          cpu,
			Memory:       memory,
			JVMMemory:    jvmMemory,
			Price:        make(map[string]price.Unit),
		}
		rb.warmInstances[instanceType] = iu
		return
	}

	iu := price.InstanceUnit{
		InstanceType: instanceType,
		Family:       getInstanceFamily(instanceType),
		CPU:          cpu,
		Memory:       memory,
		JVMMemory:    jvmMemory,
		Price:        make(map[string]price.Unit),
	}

	// Set storage limits from InstanceLimitsMap or parse internal storage
	storageStr := attrs.Storage
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

	rb.hotInstances[instanceType] = iu
}

func (rb *regionBuilder) setOnDemandPrice(instanceType, rateCode string, cnyPrice float64) {
	if instanceType == "" {
		return
	}

	if strings.HasPrefix(instanceType, "ultrawarm") {
		iu, ok := rb.warmInstances[instanceType]
		if !ok {
			return
		}
		iu.Price["OnDemand"] = price.Unit{RateCode: rateCode, Price: cnyPrice}
		rb.warmInstances[instanceType] = iu
		return
	}

	iu, ok := rb.hotInstances[instanceType]
	if !ok {
		return
	}
	iu.Price["OnDemand"] = price.Unit{RateCode: rateCode, Price: cnyPrice}
	rb.hotInstances[instanceType] = iu
}

func (rb *regionBuilder) setStoragePrice(storageMedia, rateCode string, cnyPrice float64) {
	unit := price.Unit{RateCode: rateCode, Price: cnyPrice}
	switch storageMedia {
	case "GP2":
		rb.storage.Gp2 = unit
	case "GP3":
		rb.storage.Gp3 = unit
	case "GP3-PIOPS-Storage":
		rb.storage.Gp3ProvisionedIOPS = unit
	case "GP3-ThroughPut-Storage":
		rb.storage.Gp3Provisioned = unit
	case "PIOPS":
		rb.storage.Gp2ProvisionedIOPS = unit
	case "PIOPS-Storage":
		rb.storage.Gp2Provisioned = unit
	case "Magnetic":
		rb.storage.Magnetic = unit
	case "Managed-Storage":
		rb.storage.ManagedStorage = unit
	}
}

func (rb *regionBuilder) setReservedHourlyPrice(instanceType, priceKey, rateCode string, cnyPrice float64) {
	iu, ok := rb.hotInstances[instanceType]
	if !ok {
		return
	}
	key := priceKey + "HC"
	iu.Price[key] = price.Unit{RateCode: rateCode, Price: cnyPrice}
	rb.hotInstances[instanceType] = iu
}

func (rb *regionBuilder) setReservedUpfrontPrice(instanceType, priceKey, rateCode string, cnyPrice float64) {
	iu, ok := rb.hotInstances[instanceType]
	if !ok {
		return
	}
	key := priceKey + "PC"
	iu.Price[key] = price.Unit{RateCode: rateCode, Price: cnyPrice}
	rb.hotInstances[instanceType] = iu
}

func (rb *regionBuilder) build() price.ProvisionedRegion {
	return price.ProvisionedRegion{
		HotInstances:  rb.hotInstances,
		WarmInstances: rb.warmInstances,
		Storage:       rb.storage,
		Currency:      "CNY",
	}
}

// chinaPriceKey maps China Bulk API term attributes to the internal price key prefix.
// Returns keys like "PURI1", "AURI3", "NURI1", etc. matching the global region format.
func chinaPriceKey(attrs chinaTermAttributes) string {
	var prefix string
	switch attrs.PurchaseOption {
	case "Partial Upfront":
		prefix = "PURI"
	case "All Upfront":
		prefix = "AURI"
	case "No Upfront":
		prefix = "NURI"
	default:
		return ""
	}

	switch attrs.LeaseContractLength {
	case "1yr":
		return prefix + "1"
	case "3yr":
		return prefix + "3"
	default:
		return ""
	}
}

// parseCNY extracts the CNY price from a pricePerUnit map.
func parseCNY(pricePerUnit map[string]string) float64 {
	if s, ok := pricePerUnit["CNY"]; ok {
		v, _ := strconv.ParseFloat(s, 64)
		return v
	}
	return 0
}

// downloadChinaBulkPricing fetches and parses the AWS China Bulk Pricing API response.
func downloadChinaBulkPricing(url string) (*chinaBulkPricing, error) {
	client := http.Client{
		Timeout: 30 * time.Minute,
	}

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}

	res, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = res.Body.Close() }()

	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("china pricing API returned status %d for %s", res.StatusCode, url)
	}

	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, err
	}

	var bulk chinaBulkPricing
	if err := json.Unmarshal(body, &bulk); err != nil {
		return nil, fmt.Errorf("failed to parse China pricing JSON: %w", err)
	}

	return &bulk, nil
}
