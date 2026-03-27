// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"fmt"
	"math"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/commons"
)

type OCUConfiguration struct {
	OCUsPerWorker    float64
	MaxOffHeapMemory float64
}

const DefaultOCUStorageInGB = 120
const DefaultVectorCircuitBreaker = 0.52
const BytesPerGB = 1024 * 1024 * 1024

var OCUWorkers = map[string]OCUConfiguration{
	"half":    {OCUsPerWorker: 0.5, MaxOffHeapMemory: 1.5},
	"one":     {OCUsPerWorker: 1, MaxOffHeapMemory: 3},
	"two":     {OCUsPerWorker: 2, MaxOffHeapMemory: 6},
	"four":    {OCUsPerWorker: 4, MaxOffHeapMemory: 14},
	"eight":   {OCUsPerWorker: 8, MaxOffHeapMemory: 30},
	"sixteen": {OCUsPerWorker: 16, MaxOffHeapMemory: 92},
}

type WorkerConfig struct {
	Worker                OCUConfiguration
	WorkerCount           int
	Copies                int
	RequiredStorage       float64
	TotalStorage          float64 // Using int64 for larger storage values
	AvailableMemory       float64
	AvailableVectorMemory float64
	RequiredVectorMemory  float64
}

// getOCUs returns the total number of OCUs for the worker configuration.
//
// This is the number of workers times the number of OCUs per worker.
func (wc *WorkerConfig) getOCUs() float64 {
	return wc.Worker.OCUsPerWorker * float64(wc.WorkerCount)
}

// scaleForIndexing scales the worker configuration for indexing.
//
// It calculates the total storage per worker based on the OCUConfiguration and
// the number of copies. It then determines the number of workers needed to
// satisfy the required storage based on the RequiredStorage field. Finally,
// it sets the WorkerCount and updates the total storage available.
func (wc *WorkerConfig) scaleForIndexing() {
	//total storage per worker
	storagePerWorker := wc.Worker.GetStorage()
	//number of workers needed
	wc.WorkerCount = int(math.Max(float64(wc.Copies), math.Ceil(wc.RequiredStorage/storagePerWorker)))
	//total storage available
	wc.TotalStorage = storagePerWorker * float64(wc.WorkerCount)
}

// scaleForSearch scales the worker configuration for searching.
//
// It calculates the total vector memory per worker based on the OCUConfiguration
// and the number of copies. It then determines the number of workers needed to
// satisfy the required vector memory based on the RequiredVectorMemory field.
// Finally, it sets the WorkerCount and updates the total vector memory available
// and the total memory available.
func (wc *WorkerConfig) scaleForSearch() {
	//vector memory per worker
	vectorMemoryPerWorker := wc.Worker.GetVectorMemory()
	//calculate worker count based on vector memory
	workerCountByMemory := int(math.Max(float64(wc.Copies), math.Ceil(wc.RequiredVectorMemory/vectorMemoryPerWorker)))
	//calculate worker count based on storage
	workerCountByStorage := int(math.Max(float64(wc.Copies), math.Ceil(wc.RequiredStorage/wc.Worker.GetStorage())))
	//take the maximum of the two
	wc.WorkerCount = int(math.Max(float64(workerCountByMemory), float64(workerCountByStorage)))
	//total vector memory available
	wc.AvailableVectorMemory = vectorMemoryPerWorker * float64(wc.WorkerCount)
	//total memory available
	wc.AvailableMemory = wc.Worker.MaxOffHeapMemory * float64(wc.WorkerCount)
}

type VectorCollection struct {
	IndexingWorker   WorkerConfig
	SearchWorker     WorkerConfig
	RequiredStorage  float64
	AvailableStorage float64
	RequiredMemory   float64
	AvailableMemory  float64
}

// fill populates the VectorCollection fields with the results of scaling the
// indexing worker.
//
// It copies the RequiredStorage, TotalStorage, RequiredVectorMemory, and
// AvailableVectorMemory fields from the IndexingWorker to the corresponding
// fields in the VectorCollection.
func (vc *VectorCollection) fill() {
	//scaling indexing worker
	vc.RequiredStorage = vc.IndexingWorker.RequiredStorage
	vc.AvailableStorage = vc.IndexingWorker.TotalStorage
	vc.RequiredMemory = vc.IndexingWorker.RequiredVectorMemory
	vc.AvailableMemory = vc.IndexingWorker.AvailableVectorMemory
}

// getIndexOCUs returns the total number of OCUs for the indexing worker configuration.
//
// It calculates this by multiplying the number of workers by the number of OCUs per worker.
func (vc *VectorCollection) getIndexOCUs() float64 {
	return float64(vc.IndexingWorker.WorkerCount) * vc.IndexingWorker.Worker.OCUsPerWorker
}

// getSearchOCUs returns the total number of OCUs for the search worker configuration.
//
// It calculates this by multiplying the number of workers by the number of OCUs per worker.
func (vc *VectorCollection) getSearchOCUs() float64 {
	return float64(vc.SearchWorker.WorkerCount) * vc.SearchWorker.Worker.OCUsPerWorker
}

// GetVectorMemory returns the amount of memory available for vectors for the given worker configuration.
//
// It multiplies the `MaxOffHeapMemory` field of the worker configuration by the `DefaultVectorCircuitBreaker` constant.
func (oc *OCUConfiguration) GetVectorMemory() float64 {
	return oc.MaxOffHeapMemory * DefaultVectorCircuitBreaker
}

// GetStorage returns the amount of storage available for vectors for the given worker configuration.
//
// It multiplies the `DefaultOCUStorageInGB` constant by the `OCUsPerWorker` field of the worker configuration.
func (oc *OCUConfiguration) GetStorage() float64 {
	return DefaultOCUStorageInGB * oc.OCUsPerWorker
}

// calculateV2 calculates the required workers for the given vector
// and the memory and storage requirements for indexing and searching
func (v *Vector) calculateV2() {

	// Total memory required for indexing and searching
	vectorGraphSizeInMemory, err := v.CalculateRequiredMemory()
	if err != nil {
		return
	}

	// Apply compression to non-vector data storage if enabled
	// Note: Vector data has its own compression (on-disk mode), so we use TimeSeries ratios for the non-vector document data
	compressionMultiplier := commons.GetCompressionMultiplier(v.DerivedSource, v.ZstdCompression, commons.TimeSeriesCompressionRatios)
	compressedDataSize := v.DataSize * compressionMultiplier

	// Total storage required for indexing and searching
	// Note: indexingReplica is the number of replicas for indexing
	//       v.Replicas is the number of replicas for searching
	//       We use the maximum of the two because we need to store the data
	//       and the vector graph for each replica
	indexingReplica := math.Max(1, float64(1+v.Replicas))
	totalIndexingStorage := (compressedDataSize * indexingReplica) + vectorGraphSizeInMemory

	// Calculate the best indexing workers based on the required memory
	indexingFleet := make([]WorkerConfig, 0, len(OCUWorkers))
	for _, worker := range OCUWorkers {
		iw := WorkerConfig{
			Worker:          worker,
			Copies:          int(1 + v.Replicas),
			RequiredStorage: totalIndexingStorage,
		}
		iw.scaleForIndexing()
		indexingFleet = append(indexingFleet, iw)
	}

	// Calculate the best search workers based on the required memory
	searchingFleet := make([]WorkerConfig, 0, len(OCUWorkers))
	for _, worker := range OCUWorkers {
		sw := WorkerConfig{
			Worker:               worker,
			Copies:               int(1 + v.Replicas),
			RequiredStorage:      totalIndexingStorage,
			RequiredVectorMemory: vectorGraphSizeInMemory,
		}
		sw.scaleForSearch()
		searchingFleet = append(searchingFleet, sw)
	}

	// Now the fleets are sorted, you can select the best workers
	lowestIndexingWorker := indexingFleet[0]
	for i := 1; i < len(indexingFleet); i++ {
		if indexingFleet[i].getOCUs() < lowestIndexingWorker.getOCUs() {
			lowestIndexingWorker = indexingFleet[i]
		}
	}
	lowestSearchWorker := searchingFleet[0]
	for i := 1; i < len(searchingFleet); i++ {
		if searchingFleet[i].getOCUs() < lowestSearchWorker.getOCUs() {
			lowestSearchWorker = searchingFleet[i]
		}
	}

	vc := VectorCollection{
		IndexingWorker:  lowestIndexingWorker,
		SearchWorker:    lowestSearchWorker,
		RequiredStorage: totalIndexingStorage,
		RequiredMemory:  vectorGraphSizeInMemory,
	}
	vc.fill()

	// Set the OCUs for indexing and searching
	v.IndexOCU = vc.getIndexOCUs()
	v.SearchOCU = vc.getSearchOCUs()
	v.CollectionSize = totalIndexingStorage

	v.IndexOCUCalc = fmt.Sprintf("workers:%d * ocuPerWorker:%.1f = %.1f OCUs (requiredStorage:%.2fGB, storagePerWorker:%.2fGB)",
		lowestIndexingWorker.WorkerCount, lowestIndexingWorker.Worker.OCUsPerWorker, v.IndexOCU,
		totalIndexingStorage, lowestIndexingWorker.Worker.GetStorage())
	v.SearchOCUCalc = fmt.Sprintf("workers:%d * ocuPerWorker:%.1f = %.1f OCUs (requiredVectorMemory:%.2fGB, vectorMemoryPerWorker:%.2fGB, requiredStorage:%.2fGB)",
		lowestSearchWorker.WorkerCount, lowestSearchWorker.Worker.OCUsPerWorker, v.SearchOCU,
		vectorGraphSizeInMemory, lowestSearchWorker.Worker.GetVectorMemory(), totalIndexingStorage)
}
