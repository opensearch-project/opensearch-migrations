// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package price

import (
	"errors"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/instances"
	"math"
)

const UWUrl = "https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/es/USD/current/es-ultrawarm.json"

// const OnDemandUrl = "https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/es/USD/current/es-ondemand.json"
const StorageUrl = "https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/es/USD/current/es-storage.json"

const LocationsUrl = "https://b0.p.awsstatic.com/locations/1.0/aws/current/locations.json"
const EsOnDemandUrl = "https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/es/USD/current/es-ondemand.json"

const EsRiBaseUrl = "https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/es/USD/current/es-reservedinstance/%s/%s/%s/%s/index.json"

// ChinaPricingUrl is the AWS China Bulk Pricing API endpoint for OpenSearch.
// China regions use a separate pricing partition (amazonaws.com.cn) with CNY currency.
// This API uses a different format (SKU-based products + terms) compared to the
// metered unit maps used for global regions.
const ChinaPricingUrl = "https://pricing.cn-north-1.amazonaws.com.cn/offers/v1.0/cn/AmazonES/current/index.json"

// Isolated partition pricing URLs.
// The es-ultrawarm.json endpoints in isolated partitions contain ALL instance types
// (hot + warm) with all pricing tiers (OnDemand + RI). The es.json endpoints have the same
// data but without Instance Type names, so we use es-ultrawarm.json as the primary source.

// Secret Region (aws-iso-b) pricing URLs.
const SecretInstanceUrl = "https://calculator.aws/pricing/2.0/meteredUnitMaps/aws-iso-b/es/USD/current/es-ultrawarm.json"
const SecretStorageUrl = "https://calculator.aws/pricing/2.0/meteredUnitMaps/aws-iso-b/es/USD/current/es-storage.json"

// Top Secret Region (aws-iso) pricing URLs.
const TopSecretInstanceUrl = "https://calculator.aws/pricing/2.0/meteredUnitMaps/aws-iso/es/USD/current/es-ultrawarm.json"
const TopSecretStorageUrl = "https://calculator.aws/pricing/2.0/meteredUnitMaps/aws-iso/es/USD/current/es-storage.json"

var PricingOptions = []string{"No Upfront", "Partial Upfront", "All Upfront"}
var PricingTerms = []string{"1 year", "3 year"}
var InstanceFamilies = []string{instances.GeneralPurpose, instances.ComputeOptimized, instances.MemoryOptimized, instances.StorageOptimized, instances.OpenSearchOptimized, instances.OpenSearchOptimizedFamilyName}

type InstanceStorage struct {
	Internal int `json:"internalStorage,omitempty"`
	MinEBS   int `json:"minEBS,omitempty"`
	MaxGP3   int `json:"maxGP3,omitempty"`
	MaxGP2   int `json:"maxGP2,omitempty"`
}

type InstanceUnit struct {
	Price             map[string]Unit `json:"price,omitempty"`
	CPU               int             `json:"vCPU,omitempty"`
	Storage           InstanceStorage `json:"instanceStorage,omitempty"`
	Memory            float64         `json:"memory,omitempty"`
	JVMMemory         float64         `json:"JVMMemory,omitempty"`
	VectorStoreMemory float64         `json:"vectorStoreMemory,omitempty"`
	InstanceType      string          `json:"instanceType,omitempty"`
	Family            string          `json:"family,omitempty"`
}

type ProvisionedPrice struct {
	OnDemand Unit         `json:"onDemand"`
	AURI1    ReservedUnit `json:"auri_1"`
	AURI3    ReservedUnit `json:"auri_3"`
	NURI1    Unit         `json:"nuri_1"`
	NURI3    Unit         `json:"nuri_3"`
	PURI1    ReservedUnit `json:"puri_1"`
	PURI3    ReservedUnit `json:"puri_3"`
}

type ReservedUnit struct {
	Prepaid Unit `json:"prepaid"`
	Hourly  Unit `json:"hourly"`
}

type Storage struct {
	Gp2                Unit `json:"gp2,omitempty"`
	Gp3                Unit `json:"gp3,omitempty"`
	Gp2Provisioned     Unit `json:"gp2Provisioned,omitempty"`
	Gp3Provisioned     Unit `json:"gp3Provisioned,omitempty"`
	Gp2ProvisionedIOPS Unit `json:"gp2ProvisionedIOPS,omitempty"`
	Gp3ProvisionedIOPS Unit `json:"gp3ProvisionedIOPS,omitempty"`
	Magnetic           Unit `json:"magnetic,omitempty"`
	ManagedStorage     Unit `json:"managedStorage,omitempty"`
}

type ProvisionedRegion struct {
	HotInstances  map[string]InstanceUnit `json:"hotInstances"`
	WarmInstances map[string]InstanceUnit `json:"warmInstances"`
	Storage       Storage                 `json:"storage"`
	Currency      string                  `json:"currency,omitempty"`
}

// GetStorageUnitPrice returns the price per unit of the given storage class.
//
// Returns -1 if the storage class is not recognized.
func (pr ProvisionedRegion) GetStorageUnitPrice(storageClass string) float64 {
	switch storageClass {
	case "gp3":
		return pr.Storage.Gp3.Price
	case "gp2":
		return pr.Storage.Gp2.Price
	case "managedStorage":
		return pr.Storage.ManagedStorage.Price
	}
	return -1
}

// GetHotNode returns the InstanceUnit for the given nodeType if it exists in the region's hot instances,
// or an error if it doesn't.
//
// The error is of type error, and the message is "nodeType not found in the region".
func (pr ProvisionedRegion) GetHotNode(nodeType string) (*InstanceUnit, error) {
	iu, found := pr.HotInstances[nodeType]
	if found {
		return &iu, nil
	}
	return nil, errors.New("nodeType not found in the region")
}

// GetWarmNode returns the InstanceUnit for the given nodeType if it exists in the region's warm instances,
//
// or an error if it doesn't.
//
// The error is of type error, and the message is "nodeType not found in the region".
func (pr ProvisionedRegion) GetWarmNode(nodeType string) (*InstanceUnit, error) {
	iu, found := pr.WarmInstances[nodeType]
	if found {
		return &iu, nil
	}
	return nil, errors.New("nodeType not found in the region")
}

// HasInstanceStore returns true if the instance has instance store storage.
// It returns false if it only has EBS storage.
func (is InstanceStorage) HasInstanceStore() bool {
	return is.Internal > 0
}

// GetRequiredNodeCount returns the required number of nodes and the storage per node
// based on the given requiredCPUs, storage, azs, storageClass, internalStorage, totalVectorMemory, and breaker.
//
// If the instance has instance store storage, then the node count is based on the instance store
// and the storage per node is the instance store.
// If the instance has EBS storage, then the node count is based on the EBS storage and the storage per node
func (iu InstanceUnit) GetRequiredNodeCount(requiredCPUs int, storage int, azs int, storageClass string, internalStorage *bool, totalVectorMemory float64, breaker int) (nodeCount int, storagePerNode int) {
	// first calculate the node count based on the CPU and count should be multiple of AZs
	nodeCount = getNodeCountAlignedToAz(iu.CalculateNodeCount(requiredCPUs, totalVectorMemory, breaker), azs) // int(math.Ceil(float64(requiredCPUs) / float64(iu.CPU)))
	// calculate the storage per node based on the storage and node count
	storagePerNode = int(math.Ceil(float64(storage) / float64(nodeCount)))
	//if the instance has impl storage, then the node count is based on the impl storage
	//and the storage per node is the impl storage
	if iu.Storage.HasInstanceStore() {
		// increase the node count if the storage per node is less than the impl storage
		if storagePerNode > iu.Storage.Internal {
			tempNodeCount := int(math.Ceil(float64(storage) / float64(iu.Storage.Internal)))
			nodeCount = getNodeCountAlignedToAz(tempNodeCount, azs)
		}
		// if the storage per node is greater than the impl storage, then storage per node is the impl storage
		storagePerNode = iu.Storage.Internal

	} else {
		if internalStorage != nil && *internalStorage {
			return -1, -1
		}
		instanceLimits, found := instances.InstanceLimitsMap[iu.InstanceType]
		if found && instanceLimits.IsStorageClassSupported(storageClass) {
			maxStoragePerNode := instanceLimits.GetMaxEbsVolume(storageClass)
			if maxStoragePerNode > 0 && maxStoragePerNode < storagePerNode {
				tempNodeCount := int(math.Ceil(float64(storage) / float64(maxStoragePerNode)))
				nodeCount = getNodeCountAlignedToAz(tempNodeCount, azs)
				storagePerNode = storage / nodeCount
			}
		} else {
			return -1, -1
		}
	}

	if storagePerNode < iu.Storage.MinEBS {
		storagePerNode = iu.Storage.MinEBS
	}

	// Apply combined per-AZ and instance-family node count limit
	// Per AWS docs: effective limit is min(334 * azs, familyLimit)
	effectiveMaxNodes := instances.GetEffectiveMaxHotNodes(iu.InstanceType, azs)
	if effectiveMaxNodes > 0 && nodeCount > effectiveMaxNodes {
		return -1, -1
	}

	return
}

// getNodeCountAlignedToAz returns the number of nodes aligned to the given number of AZs.
// If the nodeCount is 0, it will be set to azs.
// If the nodeCount is not a multiple of azs, it will be increased to be a multiple of azs.
func getNodeCountAlignedToAz(nodeCount int, azs int) int {
	if nodeCount == 0 {
		nodeCount = azs
	}
	if rem := nodeCount % azs; rem != 0 {
		nodeCount += azs - rem
	}
	return nodeCount
}

// CalculateNodeCount calculates the required number of nodes based on the given requiredCPU, totalVectorMemory, and breaker.
//
// It calculates the node count based on the requiredCPU and the CPU per node, and then calculates the node count based on the totalVectorMemory and the vector memory per node.
// The node count is the maximum of these two calculations.
//
// The vector memory per node is calculated by subtracting the JVM memory per node from the total memory per node.
// The result is then multiplied by the breaker as a fraction.
//
// If totalVectorMemory is 0, the node count is based only on the requiredCPU.
//
// The returned node count is always greater than or equal to 1.
func (iu InstanceUnit) CalculateNodeCount(requiredCPU int, totalVectorMemory float64, breaker int) (nodeCount int) {
	countByCPU := math.Ceil(float64(requiredCPU) / float64(iu.CPU))
	countByMemory := 0.0
	if breaker != 0 || totalVectorMemory != 0 {
		countByMemory = math.Ceil(totalVectorMemory / iu.GetVectorMemory(breaker))
	}
	nodeCount = int(math.Ceil(math.Max(countByMemory, countByCPU)))
	return
}

// GetVectorMemory returns the vector memory available on an instance of this type,
// given as a fraction of the total memory available on the instance.
// The result is calculated by subtracting the JVM memory from the total memory,
// and then multiplying the result by the breaker as a fraction.
// The breaker is given as a percentage value, and is divided by 100 before
// being used in the calculation.
// If the breaker is 0, the result is 0.
func (iu InstanceUnit) GetVectorMemory(breaker int) float64 {
	return (iu.Memory - iu.JVMMemory) * (float64(breaker) / 100)
}

// HasRemoteStorage returns true if the instance has remote storage, false otherwise.
// It currently only returns true for the OR1 family, which is the only family
// that has remote storage.
func (iu InstanceUnit) HasRemoteStorage() bool {
	return iu.Family == instances.OpenSearchOptimized
}

// HasPricingOption returns true if the instance has the given pricing type, false otherwise.
//
// It checks if the instance has the given pricing type, and if not, it checks for the hourly cost pricing type.
// If either of these exist, it returns true, otherwise it returns false.
func (iu InstanceUnit) HasPricingOption(pricingType string) bool {
	_, found := iu.Price[pricingType]
	if found {
		return true
	}
	_, found = iu.Price[pricingType+"HC"]
	return found
}
