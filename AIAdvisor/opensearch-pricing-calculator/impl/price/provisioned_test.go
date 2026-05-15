// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package price

import "testing"

func TestInstanceUnit_GetRequiredNodeCount(t *testing.T) {
	type fields struct {
		Price             map[string]Unit
		CPU               int
		Storage           InstanceStorage
		Memory            float64
		JVMMemory         float64
		VectorStoreMemory float64
		InstanceType      string
		Family            string
	}
	type args struct {
		requiredCPUs      int
		storage           int
		azs               int
		storageClass      string
		internalStorage   *bool
		totalVectorMemory float64
		breaker           int
	}
	tests := []struct {
		name               string
		fields             fields
		args               args
		wantNodeCount      int
		wantStoragePerNode int
	}{
		{"t1",
			fields{nil, 8,
				InstanceStorage{0, 10, 8000, 0},
				64,
				32,
				16,
				"r6g.2xlarge.search",
				"Memory Optimized",
			},
			args{32, 1024, 3, "gp3", nil, 0, 0},
			6,
			171},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			iu := InstanceUnit{
				Price:             tt.fields.Price,
				CPU:               tt.fields.CPU,
				Storage:           tt.fields.Storage,
				Memory:            tt.fields.Memory,
				JVMMemory:         tt.fields.JVMMemory,
				VectorStoreMemory: tt.fields.VectorStoreMemory,
				InstanceType:      tt.fields.InstanceType,
				Family:            tt.fields.Family,
			}
			gotNodeCount, gotStoragePerNode := iu.GetRequiredNodeCount(tt.args.requiredCPUs, tt.args.storage, tt.args.azs, tt.args.storageClass, tt.args.internalStorage, tt.args.totalVectorMemory, tt.args.breaker)
			if gotNodeCount != tt.wantNodeCount {
				t.Errorf("GetRequiredNodeCount() gotNodeCount = %v, want %v", gotNodeCount, tt.wantNodeCount)
			}
			if gotStoragePerNode != tt.wantStoragePerNode {
				t.Errorf("GetRequiredNodeCount() gotStoragePerNode = %v, want %v", gotStoragePerNode, tt.wantStoragePerNode)
			}
		})
	}
}
