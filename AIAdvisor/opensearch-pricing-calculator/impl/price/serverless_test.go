// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package price

import (
	"math"
	"testing"
)

func TestSetDailyIngestOcu(t *testing.T) {
	e := Estimate{}
	e.SetDailyIngestOcu(100.0)

	if e.Day.IndexOcu != 100.0 {
		t.Errorf("expected Day.IndexOcu 100, got %f", e.Day.IndexOcu)
	}
	expectedYear := 100.0 * DaysPerYear
	if e.Year.IndexOcu != expectedYear {
		t.Errorf("expected Year.IndexOcu %f, got %f", expectedYear, e.Year.IndexOcu)
	}
	expectedMonth := expectedYear / 12
	if e.Month.IndexOcu != expectedMonth {
		t.Errorf("expected Month.IndexOcu %f, got %f", expectedMonth, e.Month.IndexOcu)
	}
}

func TestSetDailySearchOcu(t *testing.T) {
	e := Estimate{}
	e.SetDailySearchOcu(50.0)

	if e.Day.SearchOcu != 50.0 {
		t.Errorf("expected Day.SearchOcu 50, got %f", e.Day.SearchOcu)
	}
	expectedYear := 50.0 * DaysPerYear
	if e.Year.SearchOcu != expectedYear {
		t.Errorf("expected Year.SearchOcu %f, got %f", expectedYear, e.Year.SearchOcu)
	}
	expectedMonth := expectedYear / 12
	if e.Month.SearchOcu != expectedMonth {
		t.Errorf("expected Month.SearchOcu %f, got %f", expectedMonth, e.Month.SearchOcu)
	}
}

func TestSetMonthlyS3cost(t *testing.T) {
	e := Estimate{}
	e.SetMonthlyS3cost(30.0)

	if e.Month.S3Storage != 30.0 {
		t.Errorf("expected Month.S3Storage 30, got %f", e.Month.S3Storage)
	}
	expectedDay := 30.0 / DaysPerMonth
	if math.Abs(e.Day.S3Storage-expectedDay) > 0.001 {
		t.Errorf("expected Day.S3Storage %f, got %f", expectedDay, e.Day.S3Storage)
	}
	expectedYear := 30.0 * 12
	if e.Year.S3Storage != expectedYear {
		t.Errorf("expected Year.S3Storage %f, got %f", expectedYear, e.Year.S3Storage)
	}
}

func TestSetDailyIngestOcu_Zero(t *testing.T) {
	e := Estimate{}
	e.SetDailyIngestOcu(0)
	if e.Day.IndexOcu != 0 || e.Month.IndexOcu != 0 || e.Year.IndexOcu != 0 {
		t.Error("expected all zero for zero input")
	}
}

func TestUpdateTotal_NoDiscount(t *testing.T) {
	e := Estimate{Edp: 0}
	e.SetDailyIngestOcu(100)
	e.SetDailySearchOcu(50)
	e.SetMonthlyS3cost(30)
	e.UpdateTotal()

	expectedDayTotal := e.Day.IndexOcu + e.Day.SearchOcu + e.Day.S3Storage
	if math.Abs(e.Day.Total-expectedDayTotal) > 0.001 {
		t.Errorf("expected Day.Total %f, got %f", expectedDayTotal, e.Day.Total)
	}
	if e.Day.Discount != 0 {
		t.Errorf("expected Day.Discount 0, got %f", e.Day.Discount)
	}
	if e.Day.DiscountedTotal != e.Day.Total {
		t.Errorf("expected DiscountedTotal == Total with no discount")
	}
}

func TestUpdateTotal_WithDiscount(t *testing.T) {
	e := Estimate{Edp: 20} // 20% discount
	e.SetDailyIngestOcu(100)
	e.SetDailySearchOcu(100)
	e.SetMonthlyS3cost(0)
	e.UpdateTotal()

	expectedDayTotal := 200.0
	expectedDiscount := expectedDayTotal * 0.2
	expectedDiscountedTotal := expectedDayTotal - expectedDiscount

	if math.Abs(e.Day.Total-expectedDayTotal) > 0.001 {
		t.Errorf("expected Day.Total %f, got %f", expectedDayTotal, e.Day.Total)
	}
	if math.Abs(e.Day.Discount-expectedDiscount) > 0.001 {
		t.Errorf("expected Day.Discount %f, got %f", expectedDiscount, e.Day.Discount)
	}
	if math.Abs(e.Day.DiscountedTotal-expectedDiscountedTotal) > 0.001 {
		t.Errorf("expected Day.DiscountedTotal %f, got %f", expectedDiscountedTotal, e.Day.DiscountedTotal)
	}

	// Verify year and month are also updated
	if e.Year.Total == 0 {
		t.Error("expected Year.Total to be non-zero")
	}
	if e.Month.Total == 0 {
		t.Error("expected Month.Total to be non-zero")
	}
}

func TestDaysPerMonth_Value(t *testing.T) {
	expected := 365.0 / 12.0
	if math.Abs(DaysPerMonth-expected) > 0.001 {
		t.Errorf("expected DaysPerMonth %f, got %f", expected, DaysPerMonth)
	}
}

// --- ProvisionedRegion tests ---

func TestGetStorageUnitPrice_GP3(t *testing.T) {
	pr := ProvisionedRegion{
		Storage: Storage{Gp3: Unit{Price: 0.08}},
	}
	if pr.GetStorageUnitPrice("gp3") != 0.08 {
		t.Errorf("expected 0.08, got %f", pr.GetStorageUnitPrice("gp3"))
	}
}

func TestGetStorageUnitPrice_GP2(t *testing.T) {
	pr := ProvisionedRegion{
		Storage: Storage{Gp2: Unit{Price: 0.10}},
	}
	if pr.GetStorageUnitPrice("gp2") != 0.10 {
		t.Errorf("expected 0.10, got %f", pr.GetStorageUnitPrice("gp2"))
	}
}

func TestGetStorageUnitPrice_ManagedStorage(t *testing.T) {
	pr := ProvisionedRegion{
		Storage: Storage{ManagedStorage: Unit{Price: 0.024}},
	}
	if pr.GetStorageUnitPrice("managedStorage") != 0.024 {
		t.Errorf("expected 0.024, got %f", pr.GetStorageUnitPrice("managedStorage"))
	}
}

func TestGetStorageUnitPrice_Unknown(t *testing.T) {
	pr := ProvisionedRegion{}
	if pr.GetStorageUnitPrice("unknown") != -1 {
		t.Errorf("expected -1 for unknown storage class")
	}
}

func TestGetHotNode_Found(t *testing.T) {
	pr := ProvisionedRegion{
		HotInstances: map[string]InstanceUnit{
			"r6g.large": {CPU: 2, Memory: 16},
		},
	}
	node, err := pr.GetHotNode("r6g.large")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if node.CPU != 2 {
		t.Errorf("expected CPU 2, got %d", node.CPU)
	}
}

func TestGetHotNode_NotFound(t *testing.T) {
	pr := ProvisionedRegion{
		HotInstances: map[string]InstanceUnit{},
	}
	_, err := pr.GetHotNode("r6g.large")
	if err == nil {
		t.Error("expected error for missing node")
	}
}

func TestGetWarmNode_Found(t *testing.T) {
	pr := ProvisionedRegion{
		WarmInstances: map[string]InstanceUnit{
			"ultrawarm1.medium": {CPU: 2, Memory: 8},
		},
	}
	node, err := pr.GetWarmNode("ultrawarm1.medium")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if node.CPU != 2 {
		t.Errorf("expected CPU 2, got %d", node.CPU)
	}
}

func TestGetWarmNode_NotFound(t *testing.T) {
	pr := ProvisionedRegion{
		WarmInstances: map[string]InstanceUnit{},
	}
	_, err := pr.GetWarmNode("ultrawarm1.medium")
	if err == nil {
		t.Error("expected error for missing node")
	}
}

func TestHasInstanceStore_True(t *testing.T) {
	is := InstanceStorage{Internal: 1024}
	if !is.HasInstanceStore() {
		t.Error("expected HasInstanceStore to return true")
	}
}

func TestHasInstanceStore_False(t *testing.T) {
	is := InstanceStorage{Internal: 0}
	if is.HasInstanceStore() {
		t.Error("expected HasInstanceStore to return false")
	}
}

func TestGetVectorMemory(t *testing.T) {
	iu := InstanceUnit{Memory: 64, JVMMemory: 32}
	// (64 - 32) * (50 / 100) = 16
	result := iu.GetVectorMemory(50)
	if math.Abs(result-16.0) > 0.001 {
		t.Errorf("expected 16.0, got %f", result)
	}
}

func TestGetVectorMemory_ZeroBreaker(t *testing.T) {
	iu := InstanceUnit{Memory: 64, JVMMemory: 32}
	result := iu.GetVectorMemory(0)
	if result != 0 {
		t.Errorf("expected 0 for zero breaker, got %f", result)
	}
}

func TestCalculateNodeCount_CPUOnly(t *testing.T) {
	iu := InstanceUnit{CPU: 4, Memory: 32, JVMMemory: 16}
	count := iu.CalculateNodeCount(10, 0, 0)
	expected := int(math.Ceil(10.0 / 4.0)) // 3
	if count != expected {
		t.Errorf("expected %d nodes, got %d", expected, count)
	}
}

func TestCalculateNodeCount_MemoryDominant(t *testing.T) {
	iu := InstanceUnit{CPU: 32, Memory: 64, JVMMemory: 32}
	// By CPU: ceil(2/32) = 1
	// By memory: ceil(100 / ((64-32) * 50/100)) = ceil(100/16) = 7
	count := iu.CalculateNodeCount(2, 100, 50)
	if count < 7 {
		t.Errorf("expected >= 7 nodes (memory dominant), got %d", count)
	}
}

func TestGetNodeCountAlignedToAz(t *testing.T) {
	tests := []struct {
		count, azs, expected int
	}{
		{0, 3, 3},     // zero nodes → set to AZ count
		{3, 3, 3},     // already aligned
		{4, 3, 6},     // 4 → next multiple of 3
		{5, 3, 6},     // 5 → next multiple of 3
		{6, 3, 6},     // already aligned
		{1, 2, 2},     // 1 → round up to 2
		{10, 3, 12},   // 10 → round up to 12
	}
	for _, tt := range tests {
		result := getNodeCountAlignedToAz(tt.count, tt.azs)
		if result != tt.expected {
			t.Errorf("getNodeCountAlignedToAz(%d, %d) = %d, expected %d", tt.count, tt.azs, result, tt.expected)
		}
	}
}

func TestHasRemoteStorage(t *testing.T) {
	iu := InstanceUnit{Family: "OR1"}
	if !iu.HasRemoteStorage() {
		t.Error("expected HasRemoteStorage true for OR1 family")
	}

	iu2 := InstanceUnit{Family: "Memory optimized"}
	if iu2.HasRemoteStorage() {
		t.Error("expected HasRemoteStorage false for non-OR1 family")
	}
}

func TestHasPricingOption_Direct(t *testing.T) {
	iu := InstanceUnit{
		Price: map[string]Unit{
			"OnDemand": {Price: 0.5},
		},
	}
	if !iu.HasPricingOption("OnDemand") {
		t.Error("expected to find OnDemand pricing")
	}
}

func TestHasPricingOption_HCVariant(t *testing.T) {
	iu := InstanceUnit{
		Price: map[string]Unit{
			"NURI_1HC": {Price: 0.3},
		},
	}
	if !iu.HasPricingOption("NURI_1") {
		t.Error("expected to find NURI_1 via HC variant")
	}
}

func TestHasPricingOption_NotFound(t *testing.T) {
	iu := InstanceUnit{
		Price: map[string]Unit{},
	}
	if iu.HasPricingOption("NonExistent") {
		t.Error("expected to not find NonExistent pricing")
	}
}
