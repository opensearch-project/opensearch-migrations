// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package instances

import "strings"

// MaxHotNodesPerAZ is the maximum number of hot nodes allowed per Availability Zone
// per AWS documentation: https://docs.aws.amazon.com/opensearch-service/latest/developerguide/limits.html
const MaxHotNodesPerAZ = 334

// GetEffectiveMaxHotNodes returns the effective maximum hot node count for an instance type
// considering both the per-AZ limit (334 per AZ) and the instance-family limit.
// The effective limit is min(334 * azs, familyLimit).
// Returns the per-AZ limit if the instance type is not found or has no MaxNodesCount defined.
func GetEffectiveMaxHotNodes(instanceType string, azs int) int {
	perAZLimit := MaxHotNodesPerAZ * azs

	instanceLimits, found := InstanceLimitsMap[instanceType]
	if !found || instanceLimits.MaxNodesCount <= 0 {
		return perAZLimit
	}

	if instanceLimits.MaxNodesCount < perAZLimit {
		return instanceLimits.MaxNodesCount
	}
	return perAZLimit
}

type InstanceLimits struct {
	Minimum       int `json:"minimum,omitempty"`
	MaximumGp3    int `json:"maximumGp3,omitempty"`
	MaximumGp2    int `json:"maximumGp2,omitempty"`
	MaxNodesCount int `json:"maxNodesCount,omitempty"`
}

var InstanceLimitsMap = map[string]InstanceLimits{
	"t2.micro.search":       {Minimum: 10, MaximumGp2: 35, MaxNodesCount: 10},
	"t2.small.search":       {Minimum: 10, MaximumGp2: 35, MaxNodesCount: 10},
	"t2.medium.search":      {Minimum: 10, MaximumGp2: 35, MaxNodesCount: 10},
	"t3.small.search":       {Minimum: 10, MaximumGp2: 100, MaximumGp3: 100, MaxNodesCount: 10},
	"t3.medium.search":      {Minimum: 10, MaximumGp2: 200, MaximumGp3: 200, MaxNodesCount: 10},
	"m3.medium.search":      {Minimum: 10, MaximumGp2: 100, MaxNodesCount: 200},
	"m3.large.search":       {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"m3.xlarge.search":      {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"m3.2xlarge.search":     {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"m4.large.search":       {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"m4.xlarge.search":      {Minimum: 10, MaximumGp2: 1024, MaxNodesCount: 200},
	"m4.2xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"m4.4xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"m4.10xlarge.search":    {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"m5.large.search":       {Minimum: 10, MaximumGp2: 512, MaximumGp3: 1024, MaxNodesCount: 200},
	"m5.xlarge.search":      {Minimum: 10, MaximumGp2: 1024, MaximumGp3: 2048, MaxNodesCount: 200},
	"m5.2xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaximumGp3: 3072, MaxNodesCount: 200},
	"m5.4xlarge.search":     {Minimum: 10, MaximumGp2: 3072, MaximumGp3: 6144, MaxNodesCount: 200},
	"m5.12xlarge.search":    {Minimum: 10, MaximumGp2: 9216, MaximumGp3: 18432, MaxNodesCount: 200},
	"m6g.large.search":      {Minimum: 10, MaximumGp2: 512, MaximumGp3: 1024, MaxNodesCount: 400},
	"m6g.xlarge.search":     {Minimum: 10, MaximumGp2: 1024, MaximumGp3: 2048, MaxNodesCount: 400},
	"m6g.2xlarge.search":    {Minimum: 10, MaximumGp2: 1536, MaximumGp3: 3072, MaxNodesCount: 400},
	"m6g.4xlarge.search":    {Minimum: 10, MaximumGp2: 3072, MaximumGp3: 6144, MaxNodesCount: 400},
	"m6g.8xlarge.search":    {Minimum: 10, MaximumGp2: 6144, MaximumGp3: 12288, MaxNodesCount: 400},
	"m6g.12xlarge.search":   {Minimum: 10, MaximumGp2: 9216, MaximumGp3: 18432, MaxNodesCount: 400},
	"m7g.large.search":      {Minimum: 10, MaximumGp3: 768, MaxNodesCount: 400},
	"m7g.xlarge.search":     {Minimum: 10, MaximumGp3: 2048, MaxNodesCount: 400},
	"m7g.2xlarge.search":    {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"m7g.4xlarge.search":    {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"m7g.8xlarge.search":    {Minimum: 10, MaximumGp3: 12288, MaxNodesCount: 400},
	"m7g.12xlarge.search":   {Minimum: 10, MaximumGp3: 18432, MaxNodesCount: 400},
	"m7g.16xlarge.search":   {Minimum: 10, MaximumGp3: 24576, MaxNodesCount: 400},
	"m8g.medium.search":     {Minimum: 10, MaximumGp3: 512, MaxNodesCount: 400},
	"m8g.large.search":      {Minimum: 10, MaximumGp3: 768, MaxNodesCount: 400},
	"m8g.xlarge.search":     {Minimum: 10, MaximumGp3: 2048, MaxNodesCount: 400},
	"m8g.2xlarge.search":    {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"m8g.4xlarge.search":    {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"m8g.8xlarge.search":    {Minimum: 10, MaximumGp3: 12288, MaxNodesCount: 400},
	"m8g.12xlarge.search":   {Minimum: 10, MaximumGp3: 18432, MaxNodesCount: 400},
	"m8g.16xlarge.search":   {Minimum: 10, MaximumGp3: 24576, MaxNodesCount: 400},
	"m7i.large.search":      {Minimum: 10, MaximumGp3: 768, MaxNodesCount: 400},
	"m7i.xlarge.search":     {Minimum: 10, MaximumGp3: 2048, MaxNodesCount: 400},
	"m7i.2xlarge.search":    {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"m7i.4xlarge.search":    {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"m7i.8xlarge.search":    {Minimum: 10, MaximumGp3: 12288, MaxNodesCount: 400},
	"m7i.12xlarge.search":   {Minimum: 10, MaximumGp3: 18432, MaxNodesCount: 400},
	"m7i.16xlarge.search":   {Minimum: 10, MaximumGp3: 24576, MaxNodesCount: 400},
	"c4.large.search":       {Minimum: 10, MaximumGp2: 100, MaxNodesCount: 200},
	"c4.xlarge.search":      {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"c4.2xlarge.search":     {Minimum: 10, MaximumGp2: 1024, MaxNodesCount: 200},
	"c4.4xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"c4.8xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"c5.large.search":       {Minimum: 10, MaximumGp2: 256, MaximumGp3: 256, MaxNodesCount: 200},
	"c5.xlarge.search":      {Minimum: 10, MaximumGp2: 512, MaximumGp3: 512, MaxNodesCount: 200},
	"c5.2xlarge.search":     {Minimum: 10, MaximumGp2: 1024, MaximumGp3: 1024, MaxNodesCount: 200},
	"c5.4xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaximumGp3: 1536, MaxNodesCount: 200},
	"c5.9xlarge.search":     {Minimum: 10, MaximumGp2: 3584, MaximumGp3: 3584, MaxNodesCount: 200},
	"c5.18xlarge.search":    {Minimum: 10, MaximumGp2: 7168, MaximumGp3: 7168, MaxNodesCount: 200},
	"c6g.large.search":      {Minimum: 10, MaximumGp2: 256, MaximumGp3: 256, MaxNodesCount: 400},
	"c6g.xlarge.search":     {Minimum: 10, MaximumGp2: 512, MaximumGp3: 512, MaxNodesCount: 400},
	"c6g.2xlarge.search":    {Minimum: 10, MaximumGp2: 1024, MaximumGp3: 1024, MaxNodesCount: 400},
	"c6g.4xlarge.search":    {Minimum: 10, MaximumGp2: 1536, MaximumGp3: 1536, MaxNodesCount: 400},
	"c6g.8xlarge.search":    {Minimum: 10, MaximumGp2: 3072, MaximumGp3: 3072, MaxNodesCount: 400},
	"c6g.12xlarge.search":   {Minimum: 10, MaximumGp2: 4608, MaximumGp3: 4608, MaxNodesCount: 400},
	"c7g.large.search":      {Minimum: 10, MaximumGp3: 256, MaxNodesCount: 400},
	"c7g.xlarge.search":     {Minimum: 10, MaximumGp3: 512, MaxNodesCount: 400},
	"c7g.2xlarge.search":    {Minimum: 10, MaximumGp3: 1024, MaxNodesCount: 400},
	"c7g.4xlarge.search":    {Minimum: 10, MaximumGp3: 1536, MaxNodesCount: 400},
	"c7g.8xlarge.search":    {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"c7g.12xlarge.search":   {Minimum: 10, MaximumGp3: 4608, MaxNodesCount: 400},
	"c7g.16xlarge.search":   {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"c8g.large.search":      {Minimum: 10, MaximumGp3: 256, MaxNodesCount: 400},
	"c8g.xlarge.search":     {Minimum: 10, MaximumGp3: 512, MaxNodesCount: 400},
	"c8g.2xlarge.search":    {Minimum: 10, MaximumGp3: 1024, MaxNodesCount: 400},
	"c8g.4xlarge.search":    {Minimum: 10, MaximumGp3: 1536, MaxNodesCount: 400},
	"c8g.8xlarge.search":    {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"c8g.12xlarge.search":   {Minimum: 10, MaximumGp3: 4608, MaxNodesCount: 400},
	"c8g.16xlarge.search":   {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"c7i.large.search":      {Minimum: 10, MaximumGp3: 256, MaxNodesCount: 400},
	"c7i.xlarge.search":     {Minimum: 10, MaximumGp3: 512, MaxNodesCount: 400},
	"c7i.2xlarge.search":    {Minimum: 10, MaximumGp3: 1024, MaxNodesCount: 400},
	"c7i.4xlarge.search":    {Minimum: 10, MaximumGp3: 1536, MaxNodesCount: 400},
	"c7i.8xlarge.search":    {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"c7i.12xlarge.search":   {Minimum: 10, MaximumGp3: 4608, MaxNodesCount: 400},
	"c7i.16xlarge.search":   {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"r3.large.search":       {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"r3.xlarge.search":      {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"r3.2xlarge.search":     {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"r3.4xlarge.search":     {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"r3.8xlarge.search":     {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"r4.large.search":       {Minimum: 10, MaximumGp2: 1024, MaxNodesCount: 200},
	"r4.xlarge.search":      {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"r4.2xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"r4.4xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"r4.8xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"r4.16xlarge.search":    {Minimum: 10, MaximumGp2: 1536, MaxNodesCount: 200},
	"r5.large.search":       {Minimum: 10, MaximumGp2: 1024, MaximumGp3: 2048, MaxNodesCount: 200},
	"r5.xlarge.search":      {Minimum: 10, MaximumGp2: 1536, MaximumGp3: 3072, MaxNodesCount: 200},
	"r5.2xlarge.search":     {Minimum: 10, MaximumGp2: 3072, MaximumGp3: 6144, MaxNodesCount: 200},
	"r5.4xlarge.search":     {Minimum: 10, MaximumGp2: 6144, MaximumGp3: 12288, MaxNodesCount: 200},
	"r5.12xlarge.search":    {Minimum: 10, MaximumGp2: 12288, MaximumGp3: 24576, MaxNodesCount: 200},
	"r6g.large.search":      {Minimum: 10, MaximumGp2: 1024, MaximumGp3: 2048, MaxNodesCount: 400},
	"r6g.xlarge.search":     {Minimum: 10, MaximumGp2: 1536, MaximumGp3: 3072, MaxNodesCount: 400},
	"r6g.2xlarge.search":    {Minimum: 10, MaximumGp2: 3072, MaximumGp3: 6144, MaxNodesCount: 400},
	"r6g.4xlarge.search":    {Minimum: 10, MaximumGp2: 6144, MaximumGp3: 12288, MaxNodesCount: 400},
	"r6g.8xlarge.search":    {Minimum: 10, MaximumGp2: 8192, MaximumGp3: 16384, MaxNodesCount: 400},
	"r6g.12xlarge.search":   {Minimum: 10, MaximumGp2: 12288, MaximumGp3: 24576, MaxNodesCount: 400},
	"r7g.large.search":      {Minimum: 10, MaximumGp3: 1536, MaxNodesCount: 400},
	"r7g.xlarge.search":     {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"r7g.2xlarge.search":    {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"r7g.4xlarge.search":    {Minimum: 10, MaximumGp3: 12288, MaxNodesCount: 400},
	"r7g.8xlarge.search":    {Minimum: 10, MaximumGp3: 16384, MaxNodesCount: 400},
	"r7g.12xlarge.search":   {Minimum: 10, MaximumGp3: 24576, MaxNodesCount: 400},
	"r7g.16xlarge.search":   {Minimum: 10, MaximumGp3: 36864, MaxNodesCount: 400},
	"r8g.medium.search":     {Minimum: 10, MaximumGp3: 768, MaxNodesCount: 400},
	"r8g.large.search":      {Minimum: 10, MaximumGp3: 1532, MaxNodesCount: 400},
	"r8g.xlarge.search":     {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"r8g.2xlarge.search":    {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"r8g.4xlarge.search":    {Minimum: 10, MaximumGp3: 12288, MaxNodesCount: 400},
	"r8g.8xlarge.search":    {Minimum: 10, MaximumGp3: 16384, MaxNodesCount: 400},
	"r8g.12xlarge.search":   {Minimum: 10, MaximumGp3: 24576, MaxNodesCount: 400},
	"r8g.16xlarge.search":   {Minimum: 10, MaximumGp3: 36864, MaxNodesCount: 400},
	"r7i.large.search":      {Minimum: 10, MaximumGp3: 1536, MaxNodesCount: 400},
	"r7i.xlarge.search":     {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
	"r7i.2xlarge.search":    {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
	"r7i.4xlarge.search":    {Minimum: 10, MaximumGp3: 12288, MaxNodesCount: 400},
	"r7i.8xlarge.search":    {Minimum: 10, MaximumGp3: 16384, MaxNodesCount: 400},
	"r7i.12xlarge.search":   {Minimum: 10, MaximumGp3: 24576, MaxNodesCount: 400},
	"r7i.16xlarge.search":   {Minimum: 10, MaximumGp3: 36864, MaxNodesCount: 400},
	"i2.large.search":       {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"i2.2xlarge.search":     {Minimum: 10, MaximumGp2: 512, MaxNodesCount: 200},
	"or1.medium.search":     {Minimum: 20, MaximumGp3: 768, MaxNodesCount: 400},
	"or1.large.search":      {Minimum: 20, MaximumGp3: 1536, MaxNodesCount: 400},
	"or1.xlarge.search":     {Minimum: 20, MaximumGp3: 3072, MaxNodesCount: 1002},
	"or1.2xlarge.search":    {Minimum: 20, MaximumGp3: 6144, MaxNodesCount: 1002},
	"or1.4xlarge.search":    {Minimum: 20, MaximumGp3: 12288, MaxNodesCount: 1002},
	"or1.8xlarge.search":    {Minimum: 20, MaximumGp3: 16384, MaxNodesCount: 1002},
	"or1.12xlarge.search":   {Minimum: 20, MaximumGp3: 24576, MaxNodesCount: 1002},
	"or1.16xlarge.search":   {Minimum: 20, MaximumGp3: 32768, MaxNodesCount: 1002},
	"or2.medium.search":     {Minimum: 20, MaximumGp3: 768, MaxNodesCount: 400},
	"or2.large.search":      {Minimum: 20, MaximumGp3: 1536, MaxNodesCount: 400},
	"or2.xlarge.search":     {Minimum: 20, MaximumGp3: 3072, MaxNodesCount: 1002},
	"or2.2xlarge.search":    {Minimum: 20, MaximumGp3: 6144, MaxNodesCount: 1002},
	"or2.4xlarge.search":    {Minimum: 20, MaximumGp3: 12288, MaxNodesCount: 1002},
	"or2.8xlarge.search":    {Minimum: 20, MaximumGp3: 16384, MaxNodesCount: 1002},
	"or2.12xlarge.search":   {Minimum: 20, MaximumGp3: 24576, MaxNodesCount: 1002},
	"or2.16xlarge.search":   {Minimum: 20, MaximumGp3: 36864, MaxNodesCount: 1002},
	"om2.large.search":      {Minimum: 20, MaximumGp3: 768, MaxNodesCount: 400},
	"om2.xlarge.search":     {Minimum: 20, MaximumGp3: 2048, MaxNodesCount: 1002},
	"om2.2xlarge.search":    {Minimum: 20, MaximumGp3: 3072, MaxNodesCount: 1002},
	"om2.4xlarge.search":    {Minimum: 20, MaximumGp3: 6144, MaxNodesCount: 1002},
	"om2.8xlarge.search":    {Minimum: 20, MaximumGp3: 12288, MaxNodesCount: 1002},
	"om2.12xlarge.search":   {Minimum: 20, MaximumGp3: 18432, MaxNodesCount: 1002},
	"om2.16xlarge.search":   {Minimum: 20, MaximumGp3: 24576, MaxNodesCount: 1002},
	"oi2.large.search":      {MaxNodesCount: 400},
	"oi2.xlarge.search":     {MaxNodesCount: 1002},
	"oi2.2xlarge.search":    {MaxNodesCount: 1002},
	"oi2.4xlarge.search":    {MaxNodesCount: 1002},
	"oi2.8xlarge.search":    {MaxNodesCount: 1002},
	"oi2.12xlarge.search":   {MaxNodesCount: 1002},
	"oi2.16xlarge.search":   {MaxNodesCount: 1002},
	"i3.large.search":       {MaxNodesCount: 200},
	"i3.xlarge.search":      {MaxNodesCount: 200},
	"i3.2xlarge.search":     {MaxNodesCount: 200},
	"i3.4xlarge.search":     {MaxNodesCount: 200},
	"i3.8xlarge.search":     {MaxNodesCount: 200},
	"i3.16xlarge.search":    {MaxNodesCount: 200},
	"im4gn.large.search":    {MaxNodesCount: 400},
	"im4gn.xlarge.search":   {MaxNodesCount: 400},
	"im4gn.2xlarge.search":  {MaxNodesCount: 400},
	"im4gn.4xlarge.search":  {MaxNodesCount: 400},
	"im4gn.8xlarge.search":  {MaxNodesCount: 400},
	"im4gn.16xlarge.search": {MaxNodesCount: 400},
	"i4i.large.search":      {MaxNodesCount: 400},
	"i4i.xlarge.search":     {MaxNodesCount: 400},
	"i4i.2xlarge.search":    {MaxNodesCount: 400},
	"i4i.4xlarge.search":    {MaxNodesCount: 400},
	"i4i.8xlarge.search":    {MaxNodesCount: 400},
	"i4i.12xlarge.search":   {MaxNodesCount: 400},
	"i4i.16xlarge.search":   {MaxNodesCount: 400},
	"i4i.24xlarge.search":   {MaxNodesCount: 400},
	"i4i.32xlarge.search":   {MaxNodesCount: 400},
	"i7i.large.search":      {MaxNodesCount: 400},
	"i7i.xlarge.search":     {MaxNodesCount: 400},
	"i7i.2xlarge.search":    {MaxNodesCount: 400},
	"i7i.4xlarge.search":    {MaxNodesCount: 400},
	"i7i.8xlarge.search":    {MaxNodesCount: 400},
	"i7i.12xlarge.search":   {MaxNodesCount: 400},
	"i7i.16xlarge.search":   {MaxNodesCount: 400},
	"i4g.large.search":      {MaxNodesCount: 400},
	"i4g.xlarge.search":     {MaxNodesCount: 400},
	"i4g.2xlarge.search":    {MaxNodesCount: 400},
	"i4g.4xlarge.search":    {MaxNodesCount: 400},
	"i4g.8xlarge.search":    {MaxNodesCount: 400},
	"i4g.16xlarge.search":   {MaxNodesCount: 400},
	"i8g.large.search":      {MaxNodesCount: 400},
	"i8g.xlarge.search":     {MaxNodesCount: 400},
	"i8g.2xlarge.search":    {MaxNodesCount: 400},
	"i8g.4xlarge.search":    {MaxNodesCount: 400},
	"i8g.8xlarge.search":    {MaxNodesCount: 400},
	"i8g.16xlarge.search":   {MaxNodesCount: 400},
	"i8ge.large.search":     {MaxNodesCount: 400},
	"i8ge.xlarge.search":    {MaxNodesCount: 400},
	"i8ge.2xlarge.search":   {MaxNodesCount: 400},
	"i8ge.3xlarge.search":   {MaxNodesCount: 400},
	"i8ge.6xlarge.search":   {MaxNodesCount: 400},
	"i8ge.12xlarge.search":  {MaxNodesCount: 400},
	"i8ge.18xlarge.search":  {MaxNodesCount: 400},
	"r7gd.large.search":     {MaxNodesCount: 400},
	"r7gd.xlarge.search":    {MaxNodesCount: 400},
	"r7gd.2xlarge.search":   {MaxNodesCount: 400},
	"r7gd.4xlarge.search":   {MaxNodesCount: 400},
	"r7gd.8xlarge.search":   {MaxNodesCount: 400},
	"r7gd.12xlarge.search":  {MaxNodesCount: 400},
	"r7gd.16xlarge.search":  {MaxNodesCount: 400},
	"r8gd.medium.search":    {MaxNodesCount: 400},
	"r8gd.large.search":     {MaxNodesCount: 400},
	"r8gd.xlarge.search":    {MaxNodesCount: 400},
	"r8gd.2xlarge.search":   {MaxNodesCount: 400},
	"r8gd.4xlarge.search":   {MaxNodesCount: 400},
	"r8gd.8xlarge.search":   {MaxNodesCount: 400},
	"r8gd.12xlarge.search":  {MaxNodesCount: 400},
	"r8gd.16xlarge.search":  {MaxNodesCount: 400},
	"r6gd.large.search":     {MaxNodesCount: 400},
	"r6gd.xlarge.search":    {MaxNodesCount: 400},
	"r6gd.2xlarge.search":   {MaxNodesCount: 400},
	"r6gd.4xlarge.search":   {MaxNodesCount: 400},
	"r6gd.8xlarge.search":   {MaxNodesCount: 400},
	"r6gd.12xlarge.search":  {MaxNodesCount: 400},
	"r6gd.16xlarge.search":  {MaxNodesCount: 400},
}

// OI2WarmInstanceLimits contains storage limits for OI2 warm instances
// Per AWS documentation: https://docs.aws.amazon.com/opensearch-service/latest/developerguide/limits.html
// - Cache size = 80% of instance storage
// - Max addressable warm storage = 5 × cache size
type OI2WarmInstanceLimits struct {
	InstanceStorageGB       int `json:"instanceStorageGB"`       // Total NVMe storage in GB
	CacheSizeGB             int `json:"cacheSizeGB"`             // 80% of instance storage
	MaxAddressableStorageGB int `json:"maxAddressableStorageGB"` // 5× cache size (max warm data per node)
}

// OI2WarmInstanceLimitsMap contains storage limits for OI2 warm tier instances
// These instances use NVMe as cache with managed storage backend
var OI2WarmInstanceLimitsMap = map[string]OI2WarmInstanceLimits{
	"oi2.large.search":   {InstanceStorageGB: 468, CacheSizeGB: 375, MaxAddressableStorageGB: 1875},
	"oi2.xlarge.search":  {InstanceStorageGB: 937, CacheSizeGB: 750, MaxAddressableStorageGB: 3750},
	"oi2.2xlarge.search": {InstanceStorageGB: 1875, CacheSizeGB: 1500, MaxAddressableStorageGB: 7500},
	"oi2.4xlarge.search": {InstanceStorageGB: 3750, CacheSizeGB: 3000, MaxAddressableStorageGB: 15000},
	"oi2.8xlarge.search": {InstanceStorageGB: 7500, CacheSizeGB: 6000, MaxAddressableStorageGB: 30000},
}

// GetOI2MaxAddressableStorage returns the max addressable warm storage in GB for an OI2 instance type.
// Returns 0 if the instance type is not found. Case-insensitive matching.
func GetOI2MaxAddressableStorage(instanceType string) float64 {
	if limits, found := OI2WarmInstanceLimitsMap[strings.ToLower(instanceType)]; found {
		return float64(limits.MaxAddressableStorageGB)
	}
	return 0
}

// GetOI2CacheSize returns the cache size in GB for an OI2 instance type.
// Returns 0 if the instance type is not found. Case-insensitive matching.
func GetOI2CacheSize(instanceType string) float64 {
	if limits, found := OI2WarmInstanceLimitsMap[strings.ToLower(instanceType)]; found {
		return float64(limits.CacheSizeGB)
	}
	return 0
}

// IsOI2WarmInstance returns true if the instance type is a valid OI2 warm instance.
// Only instances defined in OI2WarmInstanceLimitsMap are considered valid. Case-insensitive matching.
func IsOI2WarmInstance(instanceType string) bool {
	_, found := OI2WarmInstanceLimitsMap[strings.ToLower(instanceType)]
	return found
}

// IsStorageClassSupported returns true if the given storageClass is supported by the instance type.
//
// Currently, gp2 is supported by all instance types, and gp3 is supported by the instance types
// which have a non-zero MaximumGp3 value in the InstanceLimitsMap.
func (ies InstanceLimits) IsStorageClassSupported(storageClass string) bool {
	if storageClass == "gp2" {
		return true
	}
	if storageClass == "gp3" {
		return ies.MaximumGp3 > 0
	}
	return false
}

// GetMaxEbsVolume returns the maximum EBS volume size in GiB supported by the instance type for the given storage class.
//
// Returns -1 if the storage class is not supported by the instance type.
func (ies InstanceLimits) GetMaxEbsVolume(storageClass string) int {
	if storageClass == "gp2" {
		return ies.MaximumGp2
	}
	if storageClass == "gp3" {
		return ies.MaximumGp3
	}
	return -1
}
