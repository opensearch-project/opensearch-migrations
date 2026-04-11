// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"github.com/stretchr/testify/assert"
	"testing"
)

func TestVectorEstimateRequestHandleConfigGroups(t *testing.T) {
	type fields struct {
		Azs              int
		Replicas         int
		DedicatedManager bool
		MinimumJVM       float64
		Config           string
	}
	tests := []struct {
		name             string
		fields           fields
		az               int
		replica          int
		minJVM           float64
		dedicatedManager bool
	}{
		{name: "devTest", fields: fields{Config: "dev"}, replica: 0, minJVM: 2.0, az: 1, dedicatedManager: false},
		{name: "production", fields: fields{Config: "production"}, replica: 1, minJVM: 8.0, az: 3, dedicatedManager: true},
		{name: "custom", fields: fields{Config: "custom", MinimumJVM: 8.0, Replicas: 1, Azs: 3, DedicatedManager: true}, replica: 1, minJVM: 8.0, az: 3, dedicatedManager: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := &VectorEstimateRequest{
				Azs:              tt.fields.Azs,
				Replicas:         tt.fields.Replicas,
				DedicatedManager: tt.fields.DedicatedManager,
				MinimumJVM:       tt.fields.MinimumJVM,
				Config:           tt.fields.Config,
			}
			r.Normalize()
			assert.Equal(t, tt.minJVM, r.MinimumJVM, "minimum JVM mismatch")
			assert.Equal(t, tt.replica, r.Replicas, "Replica mismatch")
			assert.Equal(t, tt.dedicatedManager, r.DedicatedManager, "Dedicated manager mismatch")
			assert.Equal(t, tt.az, r.Azs, "AZ mismatch")
		})
	}
}
