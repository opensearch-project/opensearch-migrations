/* Generated Kubernetes type definitions */

/**
 * Time is a wrapper around time.Time which supports correct marshaling to YAML and JSON.  Wrappers are provided for many of the factory methods that the time package offers.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.Time".
 */
export type IoK8SApimachineryPkgApisMetaV1Time = string;
/**
 * Quantity is a fixed-point representation of a number. It provides convenient marshaling/unmarshaling in JSON and YAML, in addition to String() and AsInt64() accessors.
 *
 * The serialization format is:
 *
 * ``` <quantity>        ::= <signedNumber><suffix>
 *
 * 	(Note that <suffix> may be empty, from the "" case in <decimalSI>.)
 *
 * <digit>           ::= 0 | 1 | ... | 9 <digits>          ::= <digit> | <digit><digits> <number>          ::= <digits> | <digits>.<digits> | <digits>. | .<digits> <sign>            ::= "+" | "-" <signedNumber>    ::= <number> | <sign><number> <suffix>          ::= <binarySI> | <decimalExponent> | <decimalSI> <binarySI>        ::= Ki | Mi | Gi | Ti | Pi | Ei
 *
 * 	(International System of units; See: http://physics.nist.gov/cuu/Units/binary.html)
 *
 * <decimalSI>       ::= m | "" | k | M | G | T | P | E
 *
 * 	(Note that 1024 = 1Ki but 1000 = 1k; I didn't choose the capitalization.)
 *
 * <decimalExponent> ::= "e" <signedNumber> | "E" <signedNumber> ```
 *
 * No matter which of the three exponent forms is used, no quantity may represent a number greater than 2^63-1 in magnitude, nor may it have more than 3 decimal places. Numbers larger or more precise will be capped or rounded up. (E.g.: 0.1m will rounded up to 1m.) This may be extended in the future if we require larger or smaller quantities.
 *
 * When a Quantity is parsed from a string, it will remember the type of suffix it had, and will use the same type again when it is serialized.
 *
 * Before serializing, Quantity will be put in "canonical form". This means that Exponent/suffix will be adjusted up or down (with a corresponding increase or decrease in Mantissa) such that:
 *
 * - No precision is lost - No fractional digits will be emitted - The exponent (or suffix) is as large as possible.
 *
 * The sign will be omitted unless the number is negative.
 *
 * Examples:
 *
 * - 1.5 will be serialized as "1500m" - 1.5Gi will be serialized as "1536Mi"
 *
 * Note that the quantity will NEVER be internally represented by a floating point number. That is the whole point of this exercise.
 *
 * Non-canonical values will still parse as long as they are well formed, but will be re-emitted in their canonical form. (So always use canonical form, or don't diff.)
 *
 * This format is intended to make it difficult to use these numbers without writing some sort of special handling code in the hopes that that will cause implementors to also use a fixed point implementation.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.api.resource.Quantity".
 */
export type IoK8SApimachineryPkgApiResourceQuantity = string | number;
/**
 * IntOrString is a type that can hold an int32 or a string.  When used in JSON or YAML marshalling and unmarshalling, it produces or consumes the inner type.  This allows you to have, for example, a JSON field that can accept a name or number.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.util.intstr.IntOrString".
 */
export type IoK8SApimachineryPkgUtilIntstrIntOrString = number | string;
/**
 * MicroTime is version of Time with microsecond level precision.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.MicroTime".
 */
export type IoK8SApimachineryPkgApisMetaV1MicroTime = string;

export interface K8STypes {
  [k: string]: unknown;
}
/**
 * BoundObjectReference is a reference to an object that a token is bound to.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.authentication.v1.BoundObjectReference".
 */
export interface IoK8SApiAuthenticationV1 {
  /**
   * API version of the referent.
   */
  apiVersion?: string;
  /**
   * Kind of the referent. Valid kinds are 'Pod' and 'Secret'.
   */
  kind?: string;
  /**
   * Name of the referent.
   */
  name?: string;
  /**
   * UID of the referent.
   */
  uid?: string;
  [k: string]: unknown;
}
/**
 * TokenRequest requests a token for a given service account.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.authentication.v1.TokenRequest".
 */
export interface IoK8SApiAuthenticationV1TokenRequest {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec holds information about the request being evaluated
   */
  spec: IoK8SApiAuthenticationV11;
  /**
   * Status is filled in by the server and indicates whether the token can be authenticated.
   */
  status?: IoK8SApiAuthenticationV12;
  [k: string]: unknown;
}
/**
 * ObjectMeta is metadata that all persisted resources must have, which includes all objects users must create.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta".
 */
export interface IoK8SApimachineryPkgApisMetaV1 {
  /**
   * Annotations is an unstructured key value map stored with a resource that may be set by external tools to store and retrieve arbitrary metadata. They are not queryable and should be preserved when modifying objects. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/annotations
   */
  annotations?: {
    [k: string]: string;
  };
  /**
   * CreationTimestamp is a timestamp representing the server time when this object was created. It is not guaranteed to be set in happens-before order across separate operations. Clients may not set this value. It is represented in RFC3339 form and is in UTC.
   *
   * Populated by the system. Read-only. Null for lists. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  creationTimestamp?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Number of seconds allowed for this object to gracefully terminate before it will be removed from the system. Only set when deletionTimestamp is also set. May only be shortened. Read-only.
   */
  deletionGracePeriodSeconds?: number;
  /**
   * DeletionTimestamp is RFC 3339 date and time at which this resource will be deleted. This field is set by the server when a graceful deletion is requested by the user, and is not directly settable by a client. The resource is expected to be deleted (no longer visible from resource lists, and not reachable by name) after the time in this field, once the finalizers list is empty. As long as the finalizers list contains items, deletion is blocked. Once the deletionTimestamp is set, this value may not be unset or be set further into the future, although it may be shortened or the resource may be deleted prior to this time. For example, a user may request that a pod is deleted in 30 seconds. The Kubelet will react by sending a graceful termination signal to the containers in the pod. After that 30 seconds, the Kubelet will send a hard termination signal (SIGKILL) to the container and after cleanup, remove the pod from the API. In the presence of network partitions, this object may still exist after this timestamp, until an administrator or automated process can determine the resource is fully terminated. If not set, graceful deletion of the object has not been requested.
   *
   * Populated by the system when a graceful deletion is requested. Read-only. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  deletionTimestamp?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Must be empty before the object is deleted from the registry. Each entry is an identifier for the responsible component that will remove the entry from the list. If the deletionTimestamp of the object is non-nil, entries in this list can only be removed. Finalizers may be processed and removed in any order.  Order is NOT enforced because it introduces significant risk of stuck finalizers. finalizers is a shared field, any actor with permission can reorder it. If the finalizer list is processed in order, then this can lead to a situation in which the component responsible for the first finalizer in the list is waiting for a signal (field value, external system, or other) produced by a component responsible for a finalizer later in the list, resulting in a deadlock. Without enforced ordering finalizers are free to order amongst themselves and are not vulnerable to ordering changes in the list.
   */
  finalizers?: string[];
  /**
   * GenerateName is an optional prefix, used by the server, to generate a unique name ONLY IF the Name field has not been provided. If this field is used, the name returned to the client will be different than the name passed. This value will also be combined with a unique suffix. The provided value has the same validation rules as the Name field, and may be truncated by the length of the suffix required to make the value unique on the server.
   *
   * If this field is specified and the generated name exists, the server will return a 409.
   *
   * Applied only if Name is not specified. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#idempotency
   */
  generateName?: string;
  /**
   * A sequence number representing a specific generation of the desired state. Populated by the system. Read-only.
   */
  generation?: number;
  /**
   * Map of string keys and values that can be used to organize and categorize (scope and select) objects. May match selectors of replication controllers and services. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels
   */
  labels?: {
    [k: string]: string;
  };
  /**
   * ManagedFields maps workflow-id and version to the set of fields that are managed by that workflow. This is mostly for internal housekeeping, and users typically shouldn't need to set or understand this field. A workflow can be the user's name, a controller's name, or the name of a specific apply path like "ci-cd". The set of fields is always in the version that the workflow used when modifying the object.
   */
  managedFields?: IoK8SApimachineryPkgApisMetaV11[];
  /**
   * Name must be unique within a namespace. Is required when creating resources, although some resources may allow a client to request the generation of an appropriate name automatically. Name is primarily intended for creation idempotence and configuration definition. Cannot be updated. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names#names
   */
  name?: string;
  /**
   * Namespace defines the space within which each name must be unique. An empty namespace is equivalent to the "default" namespace, but "default" is the canonical representation. Not all objects are required to be scoped to a namespace - the value of this field for those objects will be empty.
   *
   * Must be a DNS_LABEL. Cannot be updated. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces
   */
  namespace?: string;
  /**
   * List of objects depended by this object. If ALL objects in the list have been deleted, this object will be garbage collected. If this object is managed by a controller, then an entry in this list will point to this controller, with the controller field set to true. There cannot be more than one managing controller.
   */
  ownerReferences?: IoK8SApimachineryPkgApisMetaV13[];
  /**
   * An opaque value that represents the internal version of this object that can be used by clients to determine when objects have changed. May be used for optimistic concurrency, change detection, and the watch operation on a resource or set of resources. Clients must treat these values as opaque and passed unmodified back to the server. They may only be valid for a particular resource or set of resources.
   *
   * Populated by the system. Read-only. Value must be treated as opaque by clients and . More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#concurrency-control-and-consistency
   */
  resourceVersion?: string;
  /**
   * Deprecated: selfLink is a legacy read-only field that is no longer populated by the system.
   */
  selfLink?: string;
  /**
   * UID is the unique in time and space value for this object. It is typically generated by the server on successful creation of a resource and is not allowed to change on PUT operations.
   *
   * Populated by the system. Read-only. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names#uids
   */
  uid?: string;
  [k: string]: unknown;
}
/**
 * ManagedFieldsEntry is a workflow-id, a FieldSet and the group version of the resource that the fieldset applies to.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.ManagedFieldsEntry".
 */
export interface IoK8SApimachineryPkgApisMetaV11 {
  /**
   * APIVersion defines the version of this resource that this field set applies to. The format is "group/version" just like the top-level APIVersion field. It is necessary to track the version of a field set because it cannot be automatically converted.
   */
  apiVersion?: string;
  /**
   * FieldsType is the discriminator for the different fields format and version. There is currently only one possible value: "FieldsV1"
   */
  fieldsType?: string;
  /**
   * FieldsV1 holds the first JSON version format as described in the "FieldsV1" type.
   */
  fieldsV1?: IoK8SApimachineryPkgApisMetaV12;
  /**
   * Manager is an identifier of the workflow managing these fields.
   */
  manager?: string;
  /**
   * Operation is the type of operation which lead to this ManagedFieldsEntry being created. The only valid values for this field are 'Apply' and 'Update'.
   */
  operation?: string;
  /**
   * Subresource is the name of the subresource used to update that object, or empty string if the object was updated through the main resource. The value of this field is used to distinguish between managers, even if they share the same name. For example, a status update will be distinct from a regular update using the same manager name. Note that the APIVersion field is not related to the Subresource field and it always corresponds to the version of the main resource.
   */
  subresource?: string;
  /**
   * Time is the timestamp of when the ManagedFields entry was added. The timestamp will also be updated if a field is added, the manager changes any of the owned fields value or removes a field. The timestamp does not update when a field is removed from the entry because another manager took it over.
   */
  time?: IoK8SApimachineryPkgApisMetaV1Time;
  [k: string]: unknown;
}
/**
 * FieldsV1 stores a set of fields in a data structure like a Trie, in JSON format.
 *
 * Each key is either a '.' representing the field itself, and will always map to an empty set, or a string representing a sub-field or item. The string will follow one of these four formats: 'f:<name>', where <name> is the name of a field in a struct, or key in a map 'v:<value>', where <value> is the exact json formatted value of a list item 'i:<index>', where <index> is position of a item in a list 'k:<keys>', where <keys> is a map of  a list item's key fields to their unique values If a key maps to an empty Fields value, the field that key represents is part of the set.
 *
 * The exact format is defined in sigs.k8s.io/structured-merge-diff
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.FieldsV1".
 */
export interface IoK8SApimachineryPkgApisMetaV12 {
  [k: string]: unknown;
}
/**
 * OwnerReference contains enough information to let you identify an owning object. An owning object must be in the same namespace as the dependent, or be cluster-scoped, so there is no namespace field.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.OwnerReference".
 */
export interface IoK8SApimachineryPkgApisMetaV13 {
  /**
   * API version of the referent.
   */
  apiVersion: string;
  /**
   * If true, AND if the owner has the "foregroundDeletion" finalizer, then the owner cannot be deleted from the key-value store until this reference is removed. See https://kubernetes.io/docs/concepts/architecture/garbage-collection/#foreground-deletion for how the garbage collector interacts with this field and enforces the foreground deletion. Defaults to false. To set this field, a user needs "delete" permission of the owner, otherwise 422 (Unprocessable Entity) will be returned.
   */
  blockOwnerDeletion?: boolean;
  /**
   * If true, this reference points to the managing controller.
   */
  controller?: boolean;
  /**
   * Kind of the referent. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind: string;
  /**
   * Name of the referent. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names#names
   */
  name: string;
  /**
   * UID of the referent. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names#uids
   */
  uid: string;
  [k: string]: unknown;
}
/**
 * TokenRequestSpec contains client provided parameters of a token request.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.authentication.v1.TokenRequestSpec".
 */
export interface IoK8SApiAuthenticationV11 {
  /**
   * Audiences are the intendend audiences of the token. A recipient of a token must identify themself with an identifier in the list of audiences of the token, and otherwise should reject the token. A token issued for multiple audiences may be used to authenticate against any of the audiences listed but implies a high degree of trust between the target audiences.
   */
  audiences: string[];
  /**
   * BoundObjectRef is a reference to an object that the token will be bound to. The token will only be valid for as long as the bound object exists. NOTE: The API server's TokenReview endpoint will validate the BoundObjectRef, but other audiences may not. Keep ExpirationSeconds small if you want prompt revocation.
   */
  boundObjectRef?: IoK8SApiAuthenticationV1;
  /**
   * ExpirationSeconds is the requested duration of validity of the request. The token issuer may return a token with a different validity duration so a client needs to check the 'expiration' field in a response.
   */
  expirationSeconds?: number;
  [k: string]: unknown;
}
/**
 * TokenRequestStatus is the result of a token request.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.authentication.v1.TokenRequestStatus".
 */
export interface IoK8SApiAuthenticationV12 {
  /**
   * ExpirationTimestamp is the time of expiration of the returned token.
   */
  expirationTimestamp: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Token is the opaque bearer token.
   */
  token: string;
  [k: string]: unknown;
}
/**
 * Scale represents a scaling request for a resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.autoscaling.v1.Scale".
 */
export interface IoK8SApiAutoscalingV1Scale {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object metadata; More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata.
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * spec defines the behavior of the scale. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status.
   */
  spec?: IoK8SApiAutoscalingV1;
  /**
   * status is the current status of the scale. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status. Read-only.
   */
  status?: IoK8SApiAutoscalingV11;
  [k: string]: unknown;
}
/**
 * ScaleSpec describes the attributes of a scale subresource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.autoscaling.v1.ScaleSpec".
 */
export interface IoK8SApiAutoscalingV1 {
  /**
   * replicas is the desired number of instances for the scaled object.
   */
  replicas?: number;
  [k: string]: unknown;
}
/**
 * ScaleStatus represents the current status of a scale subresource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.autoscaling.v1.ScaleStatus".
 */
export interface IoK8SApiAutoscalingV11 {
  /**
   * replicas is the actual number of observed instances of the scaled object.
   */
  replicas: number;
  /**
   * selector is the label query over pods that should match the replicas count. This is same as the label selector but in the string format to avoid introspection by clients. The string will be in the same format as the query-param syntax. More info about label selectors: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/
   */
  selector?: string;
  [k: string]: unknown;
}
/**
 * Represents a Persistent Disk resource in AWS.
 *
 * An AWS EBS disk must exist before mounting to a container. The disk must also be in the same AWS zone as the kubelet. An AWS EBS disk can only be mounted as read/write once. AWS EBS volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.AWSElasticBlockStoreVolumeSource".
 */
export interface IoK8SApiCoreV1 {
  /**
   * fsType is the filesystem type of the volume that you want to mount. Tip: Ensure that the filesystem type is supported by the host operating system. Examples: "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified. More info: https://kubernetes.io/docs/concepts/storage/volumes#awselasticblockstore
   */
  fsType?: string;
  /**
   * partition is the partition in the volume that you want to mount. If omitted, the default is to mount by volume name. Examples: For volume /dev/sda1, you specify the partition as "1". Similarly, the volume partition for /dev/sda is "0" (or you can leave the property empty).
   */
  partition?: number;
  /**
   * readOnly value true will force the readOnly setting in VolumeMounts. More info: https://kubernetes.io/docs/concepts/storage/volumes#awselasticblockstore
   */
  readOnly?: boolean;
  /**
   * volumeID is unique ID of the persistent disk resource in AWS (Amazon EBS volume). More info: https://kubernetes.io/docs/concepts/storage/volumes#awselasticblockstore
   */
  volumeID: string;
  [k: string]: unknown;
}
/**
 * Affinity is a group of affinity scheduling rules.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Affinity".
 */
export interface IoK8SApiCoreV11 {
  /**
   * Describes node affinity scheduling rules for the pod.
   */
  nodeAffinity?: IoK8SApiCoreV12;
  /**
   * Describes pod affinity scheduling rules (e.g. co-locate this pod in the same node, zone, etc. as some other pod(s)).
   */
  podAffinity?: IoK8SApiCoreV17;
  /**
   * Describes pod anti-affinity scheduling rules (e.g. avoid putting this pod in the same node, zone, etc. as some other pod(s)).
   */
  podAntiAffinity?: IoK8SApiCoreV110;
  [k: string]: unknown;
}
/**
 * Node affinity is a group of node affinity scheduling rules.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeAffinity".
 */
export interface IoK8SApiCoreV12 {
  /**
   * The scheduler will prefer to schedule pods to nodes that satisfy the affinity expressions specified by this field, but it may choose a node that violates one or more of the expressions. The node that is most preferred is the one with the greatest sum of weights, i.e. for each node that meets all of the scheduling requirements (resource request, requiredDuringScheduling affinity expressions, etc.), compute a sum by iterating through the elements of this field and adding "weight" to the sum if the node matches the corresponding matchExpressions; the node(s) with the highest sum are the most preferred.
   */
  preferredDuringSchedulingIgnoredDuringExecution?: IoK8SApiCoreV13[];
  /**
   * If the affinity requirements specified by this field are not met at scheduling time, the pod will not be scheduled onto the node. If the affinity requirements specified by this field cease to be met at some point during pod execution (e.g. due to an update), the system may or may not try to eventually evict the pod from its node.
   */
  requiredDuringSchedulingIgnoredDuringExecution?: IoK8SApiCoreV16;
  [k: string]: unknown;
}
/**
 * An empty preferred scheduling term matches all objects with implicit weight 0 (i.e. it's a no-op). A null preferred scheduling term matches no objects (i.e. is also a no-op).
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PreferredSchedulingTerm".
 */
export interface IoK8SApiCoreV13 {
  /**
   * A node selector term, associated with the corresponding weight.
   */
  preference: IoK8SApiCoreV14;
  /**
   * Weight associated with matching the corresponding nodeSelectorTerm, in the range 1-100.
   */
  weight: number;
  [k: string]: unknown;
}
/**
 * A null or empty node selector term matches no objects. The requirements of them are ANDed. The TopologySelectorTerm type implements a subset of the NodeSelectorTerm.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeSelectorTerm".
 */
export interface IoK8SApiCoreV14 {
  /**
   * A list of node selector requirements by node's labels.
   */
  matchExpressions?: IoK8SApiCoreV15[];
  /**
   * A list of node selector requirements by node's fields.
   */
  matchFields?: IoK8SApiCoreV15[];
  [k: string]: unknown;
}
/**
 * A node selector requirement is a selector that contains values, a key, and an operator that relates the key and values.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeSelectorRequirement".
 */
export interface IoK8SApiCoreV15 {
  /**
   * The label key that the selector applies to.
   */
  key: string;
  /**
   * Represents a key's relationship to a set of values. Valid operators are In, NotIn, Exists, DoesNotExist. Gt, and Lt.
   *
   * Possible enum values:
   *  - `"DoesNotExist"`
   *  - `"Exists"`
   *  - `"Gt"`
   *  - `"In"`
   *  - `"Lt"`
   *  - `"NotIn"`
   */
  operator: "DoesNotExist" | "Exists" | "Gt" | "In" | "Lt" | "NotIn";
  /**
   * An array of string values. If the operator is In or NotIn, the values array must be non-empty. If the operator is Exists or DoesNotExist, the values array must be empty. If the operator is Gt or Lt, the values array must have a single element, which will be interpreted as an integer. This array is replaced during a strategic merge patch.
   */
  values?: string[];
  [k: string]: unknown;
}
/**
 * A node selector represents the union of the results of one or more label queries over a set of nodes; that is, it represents the OR of the selectors represented by the node selector terms.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeSelector".
 */
export interface IoK8SApiCoreV16 {
  /**
   * Required. A list of node selector terms. The terms are ORed.
   */
  nodeSelectorTerms: IoK8SApiCoreV14[];
  [k: string]: unknown;
}
/**
 * Pod affinity is a group of inter pod affinity scheduling rules.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodAffinity".
 */
export interface IoK8SApiCoreV17 {
  /**
   * The scheduler will prefer to schedule pods to nodes that satisfy the affinity expressions specified by this field, but it may choose a node that violates one or more of the expressions. The node that is most preferred is the one with the greatest sum of weights, i.e. for each node that meets all of the scheduling requirements (resource request, requiredDuringScheduling affinity expressions, etc.), compute a sum by iterating through the elements of this field and adding "weight" to the sum if the node has pods which matches the corresponding podAffinityTerm; the node(s) with the highest sum are the most preferred.
   */
  preferredDuringSchedulingIgnoredDuringExecution?: IoK8SApiCoreV18[];
  /**
   * If the affinity requirements specified by this field are not met at scheduling time, the pod will not be scheduled onto the node. If the affinity requirements specified by this field cease to be met at some point during pod execution (e.g. due to a pod label update), the system may or may not try to eventually evict the pod from its node. When there are multiple elements, the lists of nodes corresponding to each podAffinityTerm are intersected, i.e. all terms must be satisfied.
   */
  requiredDuringSchedulingIgnoredDuringExecution?: IoK8SApiCoreV19[];
  [k: string]: unknown;
}
/**
 * The weights of all of the matched WeightedPodAffinityTerm fields are added per-node to find the most preferred node(s)
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.WeightedPodAffinityTerm".
 */
export interface IoK8SApiCoreV18 {
  /**
   * Required. A pod affinity term, associated with the corresponding weight.
   */
  podAffinityTerm: IoK8SApiCoreV19;
  /**
   * weight associated with matching the corresponding podAffinityTerm, in the range 1-100.
   */
  weight: number;
  [k: string]: unknown;
}
/**
 * Defines a set of pods (namely those matching the labelSelector relative to the given namespace(s)) that this pod should be co-located (affinity) or not co-located (anti-affinity) with, where co-located is defined as running on a node whose value of the label with key <topologyKey> matches that of any node on which a pod of the set of pods is running
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodAffinityTerm".
 */
export interface IoK8SApiCoreV19 {
  /**
   * A label query over a set of resources, in this case pods. If it's null, this PodAffinityTerm matches with no Pods.
   */
  labelSelector?: IoK8SApimachineryPkgApisMetaV14;
  /**
   * MatchLabelKeys is a set of pod label keys to select which pods will be taken into consideration. The keys are used to lookup values from the incoming pod labels, those key-value labels are merged with `labelSelector` as `key in (value)` to select the group of existing pods which pods will be taken into consideration for the incoming pod's pod (anti) affinity. Keys that don't exist in the incoming pod labels will be ignored. The default value is empty. The same key is forbidden to exist in both matchLabelKeys and labelSelector. Also, matchLabelKeys cannot be set when labelSelector isn't set. This is a beta field and requires enabling MatchLabelKeysInPodAffinity feature gate (enabled by default).
   */
  matchLabelKeys?: string[];
  /**
   * MismatchLabelKeys is a set of pod label keys to select which pods will be taken into consideration. The keys are used to lookup values from the incoming pod labels, those key-value labels are merged with `labelSelector` as `key notin (value)` to select the group of existing pods which pods will be taken into consideration for the incoming pod's pod (anti) affinity. Keys that don't exist in the incoming pod labels will be ignored. The default value is empty. The same key is forbidden to exist in both mismatchLabelKeys and labelSelector. Also, mismatchLabelKeys cannot be set when labelSelector isn't set. This is a beta field and requires enabling MatchLabelKeysInPodAffinity feature gate (enabled by default).
   */
  mismatchLabelKeys?: string[];
  /**
   * A label query over the set of namespaces that the term applies to. The term is applied to the union of the namespaces selected by this field and the ones listed in the namespaces field. null selector and null or empty namespaces list means "this pod's namespace". An empty selector ({}) matches all namespaces.
   */
  namespaceSelector?: IoK8SApimachineryPkgApisMetaV14;
  /**
   * namespaces specifies a static list of namespace names that the term applies to. The term is applied to the union of the namespaces listed in this field and the ones selected by namespaceSelector. null or empty namespaces list and null namespaceSelector means "this pod's namespace".
   */
  namespaces?: string[];
  /**
   * This pod should be co-located (affinity) or not co-located (anti-affinity) with the pods matching the labelSelector in the specified namespaces, where co-located is defined as running on a node whose value of the label with key topologyKey matches that of any node on which any of the selected pods is running. Empty topologyKey is not allowed.
   */
  topologyKey: string;
  [k: string]: unknown;
}
/**
 * A label selector is a label query over a set of resources. The result of matchLabels and matchExpressions are ANDed. An empty label selector matches all objects. A null label selector matches no objects.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.LabelSelector".
 */
export interface IoK8SApimachineryPkgApisMetaV14 {
  /**
   * matchExpressions is a list of label selector requirements. The requirements are ANDed.
   */
  matchExpressions?: IoK8SApimachineryPkgApisMetaV15[];
  /**
   * matchLabels is a map of {key,value} pairs. A single {key,value} in the matchLabels map is equivalent to an element of matchExpressions, whose key field is "key", the operator is "In", and the values array contains only "value". The requirements are ANDed.
   */
  matchLabels?: {
    [k: string]: string;
  };
  [k: string]: unknown;
}
/**
 * A label selector requirement is a selector that contains values, a key, and an operator that relates the key and values.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.LabelSelectorRequirement".
 */
export interface IoK8SApimachineryPkgApisMetaV15 {
  /**
   * key is the label key that the selector applies to.
   */
  key: string;
  /**
   * operator represents a key's relationship to a set of values. Valid operators are In, NotIn, Exists and DoesNotExist.
   */
  operator: string;
  /**
   * values is an array of string values. If the operator is In or NotIn, the values array must be non-empty. If the operator is Exists or DoesNotExist, the values array must be empty. This array is replaced during a strategic merge patch.
   */
  values?: string[];
  [k: string]: unknown;
}
/**
 * Pod anti affinity is a group of inter pod anti affinity scheduling rules.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodAntiAffinity".
 */
export interface IoK8SApiCoreV110 {
  /**
   * The scheduler will prefer to schedule pods to nodes that satisfy the anti-affinity expressions specified by this field, but it may choose a node that violates one or more of the expressions. The node that is most preferred is the one with the greatest sum of weights, i.e. for each node that meets all of the scheduling requirements (resource request, requiredDuringScheduling anti-affinity expressions, etc.), compute a sum by iterating through the elements of this field and adding "weight" to the sum if the node has pods which matches the corresponding podAffinityTerm; the node(s) with the highest sum are the most preferred.
   */
  preferredDuringSchedulingIgnoredDuringExecution?: IoK8SApiCoreV18[];
  /**
   * If the anti-affinity requirements specified by this field are not met at scheduling time, the pod will not be scheduled onto the node. If the anti-affinity requirements specified by this field cease to be met at some point during pod execution (e.g. due to a pod label update), the system may or may not try to eventually evict the pod from its node. When there are multiple elements, the lists of nodes corresponding to each podAffinityTerm are intersected, i.e. all terms must be satisfied.
   */
  requiredDuringSchedulingIgnoredDuringExecution?: IoK8SApiCoreV19[];
  [k: string]: unknown;
}
/**
 * AppArmorProfile defines a pod or container's AppArmor settings.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.AppArmorProfile".
 */
export interface IoK8SApiCoreV111 {
  /**
   * localhostProfile indicates a profile loaded on the node that should be used. The profile must be preconfigured on the node to work. Must match the loaded name of the profile. Must be set if and only if type is "Localhost".
   */
  localhostProfile?: string;
  /**
   * type indicates which kind of AppArmor profile will be applied. Valid options are:
   *   Localhost - a profile pre-loaded on the node.
   *   RuntimeDefault - the container runtime's default profile.
   *   Unconfined - no AppArmor enforcement.
   *
   * Possible enum values:
   *  - `"Localhost"` indicates that a profile pre-loaded on the node should be used.
   *  - `"RuntimeDefault"` indicates that the container runtime's default AppArmor profile should be used.
   *  - `"Unconfined"` indicates that no AppArmor profile should be enforced.
   */
  type: "Localhost" | "RuntimeDefault" | "Unconfined";
  [k: string]: unknown;
}
/**
 * AttachedVolume describes a volume attached to a node
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.AttachedVolume".
 */
export interface IoK8SApiCoreV112 {
  /**
   * DevicePath represents the device path where the volume should be available
   */
  devicePath: string;
  /**
   * Name of the attached volume
   */
  name: string;
  [k: string]: unknown;
}
/**
 * AzureDisk represents an Azure Data Disk mount on the host and bind mount to the pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.AzureDiskVolumeSource".
 */
export interface IoK8SApiCoreV113 {
  /**
   * cachingMode is the Host Caching mode: None, Read Only, Read Write.
   *
   * Possible enum values:
   *  - `"None"`
   *  - `"ReadOnly"`
   *  - `"ReadWrite"`
   */
  cachingMode?: "None" | "ReadOnly" | "ReadWrite";
  /**
   * diskName is the Name of the data disk in the blob storage
   */
  diskName: string;
  /**
   * diskURI is the URI of data disk in the blob storage
   */
  diskURI: string;
  /**
   * fsType is Filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified.
   */
  fsType?: string;
  /**
   * kind expected values are Shared: multiple blob disks per storage account  Dedicated: single blob disk per storage account  Managed: azure managed data disk (only in managed availability set). defaults to shared
   *
   * Possible enum values:
   *  - `"Dedicated"`
   *  - `"Managed"`
   *  - `"Shared"`
   */
  kind?: "Dedicated" | "Managed" | "Shared";
  /**
   * readOnly Defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  [k: string]: unknown;
}
/**
 * AzureFile represents an Azure File Service mount on the host and bind mount to the pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.AzureFilePersistentVolumeSource".
 */
export interface IoK8SApiCoreV114 {
  /**
   * readOnly defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * secretName is the name of secret that contains Azure Storage Account Name and Key
   */
  secretName: string;
  /**
   * secretNamespace is the namespace of the secret that contains Azure Storage Account Name and Key default is the same as the Pod
   */
  secretNamespace?: string;
  /**
   * shareName is the azure Share Name
   */
  shareName: string;
  [k: string]: unknown;
}
/**
 * AzureFile represents an Azure File Service mount on the host and bind mount to the pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.AzureFileVolumeSource".
 */
export interface IoK8SApiCoreV115 {
  /**
   * readOnly defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * secretName is the  name of secret that contains Azure Storage Account Name and Key
   */
  secretName: string;
  /**
   * shareName is the azure share Name
   */
  shareName: string;
  [k: string]: unknown;
}
/**
 * Binding ties one object to another; for example, a pod is bound to a node by a scheduler.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Binding".
 */
export interface IoK8SApiCoreV1Binding {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * The target object that you want to bind to the standard object.
   */
  target: IoK8SApiCoreV116;
  [k: string]: unknown;
}
/**
 * ObjectReference contains enough information to let you inspect or modify the referred object.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ObjectReference".
 */
export interface IoK8SApiCoreV116 {
  /**
   * API version of the referent.
   */
  apiVersion?: string;
  /**
   * If referring to a piece of an object instead of an entire object, this string should contain a valid JSON/Go field access statement, such as desiredState.manifest.containers[2]. For example, if the object reference is to a container within a pod, this would take on a value like: "spec.containers{name}" (where "name" refers to the name of the container that triggered the event) or if no container name is specified "spec.containers[2]" (container with index 2 in this pod). This syntax is chosen only to have some well-defined way of referencing a part of an object.
   */
  fieldPath?: string;
  /**
   * Kind of the referent. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Name of the referent. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  /**
   * Namespace of the referent. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/
   */
  namespace?: string;
  /**
   * Specific resourceVersion to which this reference is made, if any. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#concurrency-control-and-consistency
   */
  resourceVersion?: string;
  /**
   * UID of the referent. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#uids
   */
  uid?: string;
  [k: string]: unknown;
}
/**
 * Represents storage that is managed by an external CSI volume driver
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.CSIPersistentVolumeSource".
 */
export interface IoK8SApiCoreV117 {
  /**
   * controllerExpandSecretRef is a reference to the secret object containing sensitive information to pass to the CSI driver to complete the CSI ControllerExpandVolume call. This field is optional, and may be empty if no secret is required. If the secret object contains more than one secret, all secrets are passed.
   */
  controllerExpandSecretRef?: IoK8SApiCoreV118;
  /**
   * controllerPublishSecretRef is a reference to the secret object containing sensitive information to pass to the CSI driver to complete the CSI ControllerPublishVolume and ControllerUnpublishVolume calls. This field is optional, and may be empty if no secret is required. If the secret object contains more than one secret, all secrets are passed.
   */
  controllerPublishSecretRef?: IoK8SApiCoreV118;
  /**
   * driver is the name of the driver to use for this volume. Required.
   */
  driver: string;
  /**
   * fsType to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs".
   */
  fsType?: string;
  /**
   * nodeExpandSecretRef is a reference to the secret object containing sensitive information to pass to the CSI driver to complete the CSI NodeExpandVolume call. This field is optional, may be omitted if no secret is required. If the secret object contains more than one secret, all secrets are passed.
   */
  nodeExpandSecretRef?: IoK8SApiCoreV118;
  /**
   * nodePublishSecretRef is a reference to the secret object containing sensitive information to pass to the CSI driver to complete the CSI NodePublishVolume and NodeUnpublishVolume calls. This field is optional, and may be empty if no secret is required. If the secret object contains more than one secret, all secrets are passed.
   */
  nodePublishSecretRef?: IoK8SApiCoreV118;
  /**
   * nodeStageSecretRef is a reference to the secret object containing sensitive information to pass to the CSI driver to complete the CSI NodeStageVolume and NodeStageVolume and NodeUnstageVolume calls. This field is optional, and may be empty if no secret is required. If the secret object contains more than one secret, all secrets are passed.
   */
  nodeStageSecretRef?: IoK8SApiCoreV118;
  /**
   * readOnly value to pass to ControllerPublishVolumeRequest. Defaults to false (read/write).
   */
  readOnly?: boolean;
  /**
   * volumeAttributes of the volume to publish.
   */
  volumeAttributes?: {
    [k: string]: string;
  };
  /**
   * volumeHandle is the unique volume name returned by the CSI volume plugin’s CreateVolume to refer to the volume on all subsequent calls. Required.
   */
  volumeHandle: string;
  [k: string]: unknown;
}
/**
 * SecretReference represents a Secret Reference. It has enough information to retrieve secret in any namespace
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SecretReference".
 */
export interface IoK8SApiCoreV118 {
  /**
   * name is unique within a namespace to reference a secret resource.
   */
  name?: string;
  /**
   * namespace defines the space within which the secret name must be unique.
   */
  namespace?: string;
  [k: string]: unknown;
}
/**
 * Represents a source location of a volume to mount, managed by an external CSI driver
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.CSIVolumeSource".
 */
export interface IoK8SApiCoreV119 {
  /**
   * driver is the name of the CSI driver that handles this volume. Consult with your admin for the correct name as registered in the cluster.
   */
  driver: string;
  /**
   * fsType to mount. Ex. "ext4", "xfs", "ntfs". If not provided, the empty value is passed to the associated CSI driver which will determine the default filesystem to apply.
   */
  fsType?: string;
  /**
   * nodePublishSecretRef is a reference to the secret object containing sensitive information to pass to the CSI driver to complete the CSI NodePublishVolume and NodeUnpublishVolume calls. This field is optional, and  may be empty if no secret is required. If the secret object contains more than one secret, all secret references are passed.
   */
  nodePublishSecretRef?: IoK8SApiCoreV120;
  /**
   * readOnly specifies a read-only configuration for the volume. Defaults to false (read/write).
   */
  readOnly?: boolean;
  /**
   * volumeAttributes stores driver-specific properties that are passed to the CSI driver. Consult your driver's documentation for supported values.
   */
  volumeAttributes?: {
    [k: string]: string;
  };
  [k: string]: unknown;
}
/**
 * LocalObjectReference contains enough information to let you locate the referenced object inside the same namespace.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LocalObjectReference".
 */
export interface IoK8SApiCoreV120 {
  /**
   * Name of the referent. This field is effectively required, but due to backwards compatibility is allowed to be empty. Instances of this type with an empty value here are almost certainly wrong. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  [k: string]: unknown;
}
/**
 * Adds and removes POSIX capabilities from running containers.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Capabilities".
 */
export interface IoK8SApiCoreV121 {
  /**
   * Added capabilities
   */
  add?: string[];
  /**
   * Removed capabilities
   */
  drop?: string[];
  [k: string]: unknown;
}
/**
 * Represents a Ceph Filesystem mount that lasts the lifetime of a pod Cephfs volumes do not support ownership management or SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.CephFSPersistentVolumeSource".
 */
export interface IoK8SApiCoreV122 {
  /**
   * monitors is Required: Monitors is a collection of Ceph monitors More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  monitors: string[];
  /**
   * path is Optional: Used as the mounted root, rather than the full Ceph tree, default is /
   */
  path?: string;
  /**
   * readOnly is Optional: Defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts. More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  readOnly?: boolean;
  /**
   * secretFile is Optional: SecretFile is the path to key ring for User, default is /etc/ceph/user.secret More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  secretFile?: string;
  /**
   * secretRef is Optional: SecretRef is reference to the authentication secret for User, default is empty. More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  secretRef?: IoK8SApiCoreV118;
  /**
   * user is Optional: User is the rados user name, default is admin More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  user?: string;
  [k: string]: unknown;
}
/**
 * Represents a Ceph Filesystem mount that lasts the lifetime of a pod Cephfs volumes do not support ownership management or SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.CephFSVolumeSource".
 */
export interface IoK8SApiCoreV123 {
  /**
   * monitors is Required: Monitors is a collection of Ceph monitors More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  monitors: string[];
  /**
   * path is Optional: Used as the mounted root, rather than the full Ceph tree, default is /
   */
  path?: string;
  /**
   * readOnly is Optional: Defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts. More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  readOnly?: boolean;
  /**
   * secretFile is Optional: SecretFile is the path to key ring for User, default is /etc/ceph/user.secret More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  secretFile?: string;
  /**
   * secretRef is Optional: SecretRef is reference to the authentication secret for User, default is empty. More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  secretRef?: IoK8SApiCoreV120;
  /**
   * user is optional: User is the rados user name, default is admin More info: https://examples.k8s.io/volumes/cephfs/README.md#how-to-use-it
   */
  user?: string;
  [k: string]: unknown;
}
/**
 * Represents a cinder volume resource in Openstack. A Cinder volume must exist before mounting to a container. The volume must also be in the same region as the kubelet. Cinder volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.CinderPersistentVolumeSource".
 */
export interface IoK8SApiCoreV124 {
  /**
   * fsType Filesystem type to mount. Must be a filesystem type supported by the host operating system. Examples: "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified. More info: https://examples.k8s.io/mysql-cinder-pd/README.md
   */
  fsType?: string;
  /**
   * readOnly is Optional: Defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts. More info: https://examples.k8s.io/mysql-cinder-pd/README.md
   */
  readOnly?: boolean;
  /**
   * secretRef is Optional: points to a secret object containing parameters used to connect to OpenStack.
   */
  secretRef?: IoK8SApiCoreV118;
  /**
   * volumeID used to identify the volume in cinder. More info: https://examples.k8s.io/mysql-cinder-pd/README.md
   */
  volumeID: string;
  [k: string]: unknown;
}
/**
 * Represents a cinder volume resource in Openstack. A Cinder volume must exist before mounting to a container. The volume must also be in the same region as the kubelet. Cinder volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.CinderVolumeSource".
 */
export interface IoK8SApiCoreV125 {
  /**
   * fsType is the filesystem type to mount. Must be a filesystem type supported by the host operating system. Examples: "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified. More info: https://examples.k8s.io/mysql-cinder-pd/README.md
   */
  fsType?: string;
  /**
   * readOnly defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts. More info: https://examples.k8s.io/mysql-cinder-pd/README.md
   */
  readOnly?: boolean;
  /**
   * secretRef is optional: points to a secret object containing parameters used to connect to OpenStack.
   */
  secretRef?: IoK8SApiCoreV120;
  /**
   * volumeID used to identify the volume in cinder. More info: https://examples.k8s.io/mysql-cinder-pd/README.md
   */
  volumeID: string;
  [k: string]: unknown;
}
/**
 * ClientIPConfig represents the configurations of Client IP based session affinity.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ClientIPConfig".
 */
export interface IoK8SApiCoreV126 {
  /**
   * timeoutSeconds specifies the seconds of ClientIP type session sticky time. The value must be >0 && <=86400(for 1 day) if ServiceAffinity == "ClientIP". Default value is 10800(for 3 hours).
   */
  timeoutSeconds?: number;
  [k: string]: unknown;
}
/**
 * ClusterTrustBundleProjection describes how to select a set of ClusterTrustBundle objects and project their contents into the pod filesystem.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ClusterTrustBundleProjection".
 */
export interface IoK8SApiCoreV127 {
  /**
   * Select all ClusterTrustBundles that match this label selector.  Only has effect if signerName is set.  Mutually-exclusive with name.  If unset, interpreted as "match nothing".  If set but empty, interpreted as "match everything".
   */
  labelSelector?: IoK8SApimachineryPkgApisMetaV14;
  /**
   * Select a single ClusterTrustBundle by object name.  Mutually-exclusive with signerName and labelSelector.
   */
  name?: string;
  /**
   * If true, don't block pod startup if the referenced ClusterTrustBundle(s) aren't available.  If using name, then the named ClusterTrustBundle is allowed not to exist.  If using signerName, then the combination of signerName and labelSelector is allowed to match zero ClusterTrustBundles.
   */
  optional?: boolean;
  /**
   * Relative path from the volume root to write the bundle.
   */
  path: string;
  /**
   * Select all ClusterTrustBundles that match this signer name. Mutually-exclusive with name.  The contents of all selected ClusterTrustBundles will be unified and deduplicated.
   */
  signerName?: string;
  [k: string]: unknown;
}
/**
 * Information about the condition of a component.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ComponentCondition".
 */
export interface IoK8SApiCoreV128 {
  /**
   * Condition error code for a component. For example, a health check error code.
   */
  error?: string;
  /**
   * Message about the condition for a component. For example, information about a health check.
   */
  message?: string;
  /**
   * Status of the condition for a component. Valid values for "Healthy": "True", "False", or "Unknown".
   */
  status: string;
  /**
   * Type of condition for a component. Valid value: "Healthy"
   */
  type: string;
  [k: string]: unknown;
}
/**
 * ComponentStatus (and ComponentStatusList) holds the cluster validation info. Deprecated: This API is deprecated in v1.19+
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ComponentStatus".
 */
export interface IoK8SApiCoreV129 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of component conditions observed
   */
  conditions?: IoK8SApiCoreV128[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  [k: string]: unknown;
}
/**
 * Status of all the conditions for the component as a list of ComponentStatus objects. Deprecated: This API is deprecated in v1.19+
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ComponentStatusList".
 */
export interface IoK8SApiCoreV1ComponentStatusList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of ComponentStatus objects.
   */
  items: IoK8SApiCoreV129[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * ListMeta describes metadata that synthetic resources must have, including lists and various status objects. A resource may have only one of {ObjectMeta, ListMeta}.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.ListMeta".
 */
export interface IoK8SApimachineryPkgApisMetaV16 {
  /**
   * continue may be set if the user set a limit on the number of items returned, and indicates that the server has more data available. The value is opaque and may be used to issue another request to the endpoint that served this list to retrieve the next set of available objects. Continuing a consistent list may not be possible if the server configuration has changed or more than a few minutes have passed. The resourceVersion field returned when using this continue value will be identical to the value in the first response, unless you have received this token from an error message.
   */
  continue?: string;
  /**
   * remainingItemCount is the number of subsequent items in the list which are not included in this list response. If the list request contained label or field selectors, then the number of remaining items is unknown and the field will be left unset and omitted during serialization. If the list is complete (either because it is not chunking or because this is the last chunk), then there are no more remaining items and this field will be left unset and omitted during serialization. Servers older than v1.15 do not set this field. The intended use of the remainingItemCount is *estimating* the size of a collection. Clients should not rely on the remainingItemCount to be set or to be exact.
   */
  remainingItemCount?: number;
  /**
   * String that identifies the server's internal version of this object that can be used by clients to determine when objects have changed. Value must be treated as opaque by clients and passed unmodified back to the server. Populated by the system. Read-only. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#concurrency-control-and-consistency
   */
  resourceVersion?: string;
  /**
   * Deprecated: selfLink is a legacy read-only field that is no longer populated by the system.
   */
  selfLink?: string;
  [k: string]: unknown;
}
/**
 * ConfigMap holds configuration data for pods to consume.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ConfigMap".
 */
export interface IoK8SApiCoreV130 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * BinaryData contains the binary data. Each key must consist of alphanumeric characters, '-', '_' or '.'. BinaryData can contain byte sequences that are not in the UTF-8 range. The keys stored in BinaryData must not overlap with the ones in the Data field, this is enforced during validation process. Using this field will require 1.10+ apiserver and kubelet.
   */
  binaryData?: {
    [k: string]: string;
  };
  /**
   * Data contains the configuration data. Each key must consist of alphanumeric characters, '-', '_' or '.'. Values with non-UTF-8 byte sequences must use the BinaryData field. The keys stored in Data must not overlap with the keys in the BinaryData field, this is enforced during validation process.
   */
  data?: {
    [k: string]: string;
  };
  /**
   * Immutable, if set to true, ensures that data stored in the ConfigMap cannot be updated (only object metadata can be modified). If not set to true, the field can be modified at any time. Defaulted to nil.
   */
  immutable?: boolean;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  [k: string]: unknown;
}
/**
 * ConfigMapEnvSource selects a ConfigMap to populate the environment variables with.
 *
 * The contents of the target ConfigMap's Data field will represent the key-value pairs as environment variables.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ConfigMapEnvSource".
 */
export interface IoK8SApiCoreV131 {
  /**
   * Name of the referent. This field is effectively required, but due to backwards compatibility is allowed to be empty. Instances of this type with an empty value here are almost certainly wrong. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  /**
   * Specify whether the ConfigMap must be defined
   */
  optional?: boolean;
  [k: string]: unknown;
}
/**
 * Selects a key from a ConfigMap.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ConfigMapKeySelector".
 */
export interface IoK8SApiCoreV132 {
  /**
   * The key to select.
   */
  key: string;
  /**
   * Name of the referent. This field is effectively required, but due to backwards compatibility is allowed to be empty. Instances of this type with an empty value here are almost certainly wrong. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  /**
   * Specify whether the ConfigMap or its key must be defined
   */
  optional?: boolean;
  [k: string]: unknown;
}
/**
 * ConfigMapList is a resource containing a list of ConfigMap objects.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ConfigMapList".
 */
export interface IoK8SApiCoreV1ConfigMapList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Items is the list of ConfigMaps.
   */
  items: IoK8SApiCoreV130[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * ConfigMapNodeConfigSource contains the information to reference a ConfigMap as a config source for the Node. This API is deprecated since 1.22: https://git.k8s.io/enhancements/keps/sig-node/281-dynamic-kubelet-configuration
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ConfigMapNodeConfigSource".
 */
export interface IoK8SApiCoreV133 {
  /**
   * KubeletConfigKey declares which key of the referenced ConfigMap corresponds to the KubeletConfiguration structure This field is required in all cases.
   */
  kubeletConfigKey: string;
  /**
   * Name is the metadata.name of the referenced ConfigMap. This field is required in all cases.
   */
  name: string;
  /**
   * Namespace is the metadata.namespace of the referenced ConfigMap. This field is required in all cases.
   */
  namespace: string;
  /**
   * ResourceVersion is the metadata.ResourceVersion of the referenced ConfigMap. This field is forbidden in Node.Spec, and required in Node.Status.
   */
  resourceVersion?: string;
  /**
   * UID is the metadata.UID of the referenced ConfigMap. This field is forbidden in Node.Spec, and required in Node.Status.
   */
  uid?: string;
  [k: string]: unknown;
}
/**
 * Adapts a ConfigMap into a projected volume.
 *
 * The contents of the target ConfigMap's Data field will be presented in a projected volume as files using the keys in the Data field as the file names, unless the items element is populated with specific mappings of keys to paths. Note that this is identical to a configmap volume source without the default mode.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ConfigMapProjection".
 */
export interface IoK8SApiCoreV134 {
  /**
   * items if unspecified, each key-value pair in the Data field of the referenced ConfigMap will be projected into the volume as a file whose name is the key and content is the value. If specified, the listed keys will be projected into the specified paths, and unlisted keys will not be present. If a key is specified which is not present in the ConfigMap, the volume setup will error unless it is marked optional. Paths must be relative and may not contain the '..' path or start with '..'.
   */
  items?: IoK8SApiCoreV135[];
  /**
   * Name of the referent. This field is effectively required, but due to backwards compatibility is allowed to be empty. Instances of this type with an empty value here are almost certainly wrong. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  /**
   * optional specify whether the ConfigMap or its keys must be defined
   */
  optional?: boolean;
  [k: string]: unknown;
}
/**
 * Maps a string key to a path within a volume.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.KeyToPath".
 */
export interface IoK8SApiCoreV135 {
  /**
   * key is the key to project.
   */
  key: string;
  /**
   * mode is Optional: mode bits used to set permissions on this file. Must be an octal value between 0000 and 0777 or a decimal value between 0 and 511. YAML accepts both octal and decimal values, JSON requires decimal values for mode bits. If not specified, the volume defaultMode will be used. This might be in conflict with other options that affect the file mode, like fsGroup, and the result can be other mode bits set.
   */
  mode?: number;
  /**
   * path is the relative path of the file to map the key to. May not be an absolute path. May not contain the path element '..'. May not start with the string '..'.
   */
  path: string;
  [k: string]: unknown;
}
/**
 * Adapts a ConfigMap into a volume.
 *
 * The contents of the target ConfigMap's Data field will be presented in a volume as files using the keys in the Data field as the file names, unless the items element is populated with specific mappings of keys to paths. ConfigMap volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ConfigMapVolumeSource".
 */
export interface IoK8SApiCoreV136 {
  /**
   * defaultMode is optional: mode bits used to set permissions on created files by default. Must be an octal value between 0000 and 0777 or a decimal value between 0 and 511. YAML accepts both octal and decimal values, JSON requires decimal values for mode bits. Defaults to 0644. Directories within the path are not affected by this setting. This might be in conflict with other options that affect the file mode, like fsGroup, and the result can be other mode bits set.
   */
  defaultMode?: number;
  /**
   * items if unspecified, each key-value pair in the Data field of the referenced ConfigMap will be projected into the volume as a file whose name is the key and content is the value. If specified, the listed keys will be projected into the specified paths, and unlisted keys will not be present. If a key is specified which is not present in the ConfigMap, the volume setup will error unless it is marked optional. Paths must be relative and may not contain the '..' path or start with '..'.
   */
  items?: IoK8SApiCoreV135[];
  /**
   * Name of the referent. This field is effectively required, but due to backwards compatibility is allowed to be empty. Instances of this type with an empty value here are almost certainly wrong. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  /**
   * optional specify whether the ConfigMap or its keys must be defined
   */
  optional?: boolean;
  [k: string]: unknown;
}
/**
 * A single application container that you want to run within a pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Container".
 */
export interface IoK8SApiCoreV137 {
  /**
   * Arguments to the entrypoint. The container image's CMD is used if this is not provided. Variable references $(VAR_NAME) are expanded using the container's environment. If a variable cannot be resolved, the reference in the input string will be unchanged. Double $$ are reduced to a single $, which allows for escaping the $(VAR_NAME) syntax: i.e. "$$(VAR_NAME)" will produce the string literal "$(VAR_NAME)". Escaped references will never be expanded, regardless of whether the variable exists or not. Cannot be updated. More info: https://kubernetes.io/docs/tasks/inject-data-application/define-command-argument-container/#running-a-command-in-a-shell
   */
  args?: string[];
  /**
   * Entrypoint array. Not executed within a shell. The container image's ENTRYPOINT is used if this is not provided. Variable references $(VAR_NAME) are expanded using the container's environment. If a variable cannot be resolved, the reference in the input string will be unchanged. Double $$ are reduced to a single $, which allows for escaping the $(VAR_NAME) syntax: i.e. "$$(VAR_NAME)" will produce the string literal "$(VAR_NAME)". Escaped references will never be expanded, regardless of whether the variable exists or not. Cannot be updated. More info: https://kubernetes.io/docs/tasks/inject-data-application/define-command-argument-container/#running-a-command-in-a-shell
   */
  command?: string[];
  /**
   * List of environment variables to set in the container. Cannot be updated.
   */
  env?: IoK8SApiCoreV138[];
  /**
   * List of sources to populate environment variables in the container. The keys defined within a source must be a C_IDENTIFIER. All invalid keys will be reported as an event when the container is starting. When a key exists in multiple sources, the value associated with the last source will take precedence. Values defined by an Env with a duplicate key will take precedence. Cannot be updated.
   */
  envFrom?: IoK8SApiCoreV143[];
  /**
   * Container image name. More info: https://kubernetes.io/docs/concepts/containers/images This field is optional to allow higher level config management to default or override container images in workload controllers like Deployments and StatefulSets.
   */
  image?: string;
  /**
   * Image pull policy. One of Always, Never, IfNotPresent. Defaults to Always if :latest tag is specified, or IfNotPresent otherwise. Cannot be updated. More info: https://kubernetes.io/docs/concepts/containers/images#updating-images
   *
   * Possible enum values:
   *  - `"Always"` means that kubelet always attempts to pull the latest image. Container will fail If the pull fails.
   *  - `"IfNotPresent"` means that kubelet pulls if the image isn't present on disk. Container will fail if the image isn't present and the pull fails.
   *  - `"Never"` means that kubelet never pulls an image, but only uses a local image. Container will fail if the image isn't present
   */
  imagePullPolicy?: "Always" | "IfNotPresent" | "Never";
  /**
   * Actions that the management system should take in response to container lifecycle events. Cannot be updated.
   */
  lifecycle?: IoK8SApiCoreV145;
  /**
   * Periodic probe of container liveness. Container will be restarted if the probe fails. Cannot be updated. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#container-probes
   */
  livenessProbe?: IoK8SApiCoreV152;
  /**
   * Name of the container specified as a DNS_LABEL. Each container in a pod must have a unique name (DNS_LABEL). Cannot be updated.
   */
  name: string;
  /**
   * List of ports to expose from the container. Not specifying a port here DOES NOT prevent that port from being exposed. Any port which is listening on the default "0.0.0.0" address inside a container will be accessible from the network. Modifying this array with strategic merge patch may corrupt the data. For more information See https://github.com/kubernetes/kubernetes/issues/108255. Cannot be updated.
   */
  ports?: IoK8SApiCoreV154[];
  /**
   * Periodic probe of container service readiness. Container will be removed from service endpoints if the probe fails. Cannot be updated. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#container-probes
   */
  readinessProbe?: IoK8SApiCoreV152;
  /**
   * Resources resize policy for the container.
   */
  resizePolicy?: IoK8SApiCoreV155[];
  /**
   * Compute Resources required by this container. Cannot be updated. More info: https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
   */
  resources?: IoK8SApiCoreV156;
  /**
   * RestartPolicy defines the restart behavior of individual containers in a pod. This field may only be set for init containers, and the only allowed value is "Always". For non-init containers or when this field is not specified, the restart behavior is defined by the Pod's restart policy and the container type. Setting the RestartPolicy as "Always" for the init container will have the following effect: this init container will be continually restarted on exit until all regular containers have terminated. Once all regular containers have completed, all init containers with restartPolicy "Always" will be shut down. This lifecycle differs from normal init containers and is often referred to as a "sidecar" container. Although this init container still starts in the init container sequence, it does not wait for the container to complete before proceeding to the next init container. Instead, the next init container starts immediately after this init container is started, or after any startupProbe has successfully completed.
   */
  restartPolicy?: string;
  /**
   * SecurityContext defines the security options the container should be run with. If set, the fields of SecurityContext override the equivalent fields of PodSecurityContext. More info: https://kubernetes.io/docs/tasks/configure-pod-container/security-context/
   */
  securityContext?: IoK8SApiCoreV158;
  /**
   * StartupProbe indicates that the Pod has successfully initialized. If specified, no other probes are executed until this completes successfully. If this probe fails, the Pod will be restarted, just as if the livenessProbe failed. This can be used to provide different probe parameters at the beginning of a Pod's lifecycle, when it might take a long time to load data or warm a cache, than during steady-state operation. This cannot be updated. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#container-probes
   */
  startupProbe?: IoK8SApiCoreV152;
  /**
   * Whether this container should allocate a buffer for stdin in the container runtime. If this is not set, reads from stdin in the container will always result in EOF. Default is false.
   */
  stdin?: boolean;
  /**
   * Whether the container runtime should close the stdin channel after it has been opened by a single attach. When stdin is true the stdin stream will remain open across multiple attach sessions. If stdinOnce is set to true, stdin is opened on container start, is empty until the first client attaches to stdin, and then remains open and accepts data until the client disconnects, at which time stdin is closed and remains closed until the container is restarted. If this flag is false, a container processes that reads from stdin will never receive an EOF. Default is false
   */
  stdinOnce?: boolean;
  /**
   * Optional: Path at which the file to which the container's termination message will be written is mounted into the container's filesystem. Message written is intended to be brief final status, such as an assertion failure message. Will be truncated by the node if greater than 4096 bytes. The total message length across all containers will be limited to 12kb. Defaults to /dev/termination-log. Cannot be updated.
   */
  terminationMessagePath?: string;
  /**
   * Indicate how the termination message should be populated. File will use the contents of terminationMessagePath to populate the container status message on both success and failure. FallbackToLogsOnError will use the last chunk of container log output if the termination message file is empty and the container exited with an error. The log output is limited to 2048 bytes or 80 lines, whichever is smaller. Defaults to File. Cannot be updated.
   *
   * Possible enum values:
   *  - `"FallbackToLogsOnError"` will read the most recent contents of the container logs for the container status message when the container exits with an error and the terminationMessagePath has no contents.
   *  - `"File"` is the default behavior and will set the container status message to the contents of the container's terminationMessagePath when the container exits.
   */
  terminationMessagePolicy?: "FallbackToLogsOnError" | "File";
  /**
   * Whether this container should allocate a TTY for itself, also requires 'stdin' to be true. Default is false.
   */
  tty?: boolean;
  /**
   * volumeDevices is the list of block devices to be used by the container.
   */
  volumeDevices?: IoK8SApiCoreV162[];
  /**
   * Pod volumes to mount into the container's filesystem. Cannot be updated.
   */
  volumeMounts?: IoK8SApiCoreV163[];
  /**
   * Container's working directory. If not specified, the container runtime's default will be used, which might be configured in the container image. Cannot be updated.
   */
  workingDir?: string;
  [k: string]: unknown;
}
/**
 * EnvVar represents an environment variable present in a Container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EnvVar".
 */
export interface IoK8SApiCoreV138 {
  /**
   * Name of the environment variable. Must be a C_IDENTIFIER.
   */
  name: string;
  /**
   * Variable references $(VAR_NAME) are expanded using the previously defined environment variables in the container and any service environment variables. If a variable cannot be resolved, the reference in the input string will be unchanged. Double $$ are reduced to a single $, which allows for escaping the $(VAR_NAME) syntax: i.e. "$$(VAR_NAME)" will produce the string literal "$(VAR_NAME)". Escaped references will never be expanded, regardless of whether the variable exists or not. Defaults to "".
   */
  value?: string;
  /**
   * Source for the environment variable's value. Cannot be used if value is not empty.
   */
  valueFrom?: IoK8SApiCoreV139;
  [k: string]: unknown;
}
/**
 * EnvVarSource represents a source for the value of an EnvVar.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EnvVarSource".
 */
export interface IoK8SApiCoreV139 {
  /**
   * Selects a key of a ConfigMap.
   */
  configMapKeyRef?: IoK8SApiCoreV132;
  /**
   * Selects a field of the pod: supports metadata.name, metadata.namespace, `metadata.labels['<KEY>']`, `metadata.annotations['<KEY>']`, spec.nodeName, spec.serviceAccountName, status.hostIP, status.podIP, status.podIPs.
   */
  fieldRef?: IoK8SApiCoreV140;
  /**
   * Selects a resource of the container: only resources limits and requests (limits.cpu, limits.memory, limits.ephemeral-storage, requests.cpu, requests.memory and requests.ephemeral-storage) are currently supported.
   */
  resourceFieldRef?: IoK8SApiCoreV141;
  /**
   * Selects a key of a secret in the pod's namespace
   */
  secretKeyRef?: IoK8SApiCoreV142;
  [k: string]: unknown;
}
/**
 * ObjectFieldSelector selects an APIVersioned field of an object.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ObjectFieldSelector".
 */
export interface IoK8SApiCoreV140 {
  /**
   * Version of the schema the FieldPath is written in terms of, defaults to "v1".
   */
  apiVersion?: string;
  /**
   * Path of the field to select in the specified API version.
   */
  fieldPath: string;
  [k: string]: unknown;
}
/**
 * ResourceFieldSelector represents container resources (cpu, memory) and their output format
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceFieldSelector".
 */
export interface IoK8SApiCoreV141 {
  /**
   * Container name: required for volumes, optional for env vars
   */
  containerName?: string;
  /**
   * Specifies the output format of the exposed resources, defaults to "1"
   */
  divisor?: IoK8SApimachineryPkgApiResourceQuantity;
  /**
   * Required: resource to select
   */
  resource: string;
  [k: string]: unknown;
}
/**
 * SecretKeySelector selects a key of a Secret.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SecretKeySelector".
 */
export interface IoK8SApiCoreV142 {
  /**
   * The key of the secret to select from.  Must be a valid secret key.
   */
  key: string;
  /**
   * Name of the referent. This field is effectively required, but due to backwards compatibility is allowed to be empty. Instances of this type with an empty value here are almost certainly wrong. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  /**
   * Specify whether the Secret or its key must be defined
   */
  optional?: boolean;
  [k: string]: unknown;
}
/**
 * EnvFromSource represents the source of a set of ConfigMaps
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EnvFromSource".
 */
export interface IoK8SApiCoreV143 {
  /**
   * The ConfigMap to select from
   */
  configMapRef?: IoK8SApiCoreV131;
  /**
   * An optional identifier to prepend to each key in the ConfigMap. Must be a C_IDENTIFIER.
   */
  prefix?: string;
  /**
   * The Secret to select from
   */
  secretRef?: IoK8SApiCoreV144;
  [k: string]: unknown;
}
/**
 * SecretEnvSource selects a Secret to populate the environment variables with.
 *
 * The contents of the target Secret's Data field will represent the key-value pairs as environment variables.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SecretEnvSource".
 */
export interface IoK8SApiCoreV144 {
  /**
   * Name of the referent. This field is effectively required, but due to backwards compatibility is allowed to be empty. Instances of this type with an empty value here are almost certainly wrong. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  /**
   * Specify whether the Secret must be defined
   */
  optional?: boolean;
  [k: string]: unknown;
}
/**
 * Lifecycle describes actions that the management system should take in response to container lifecycle events. For the PostStart and PreStop lifecycle handlers, management of the container blocks until the action is complete, unless the container process fails, in which case the handler is aborted.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Lifecycle".
 */
export interface IoK8SApiCoreV145 {
  /**
   * PostStart is called immediately after a container is created. If the handler fails, the container is terminated and restarted according to its restart policy. Other management of the container blocks until the hook completes. More info: https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/#container-hooks
   */
  postStart?: IoK8SApiCoreV146;
  /**
   * PreStop is called immediately before a container is terminated due to an API request or management event such as liveness/startup probe failure, preemption, resource contention, etc. The handler is not called if the container crashes or exits. The Pod's termination grace period countdown begins before the PreStop hook is executed. Regardless of the outcome of the handler, the container will eventually terminate within the Pod's termination grace period (unless delayed by finalizers). Other management of the container blocks until the hook completes or until the termination grace period is reached. More info: https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/#container-hooks
   */
  preStop?: IoK8SApiCoreV146;
  [k: string]: unknown;
}
/**
 * LifecycleHandler defines a specific action that should be taken in a lifecycle hook. One and only one of the fields, except TCPSocket must be specified.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LifecycleHandler".
 */
export interface IoK8SApiCoreV146 {
  /**
   * Exec specifies a command to execute in the container.
   */
  exec?: IoK8SApiCoreV147;
  /**
   * HTTPGet specifies an HTTP GET request to perform.
   */
  httpGet?: IoK8SApiCoreV148;
  /**
   * Sleep represents a duration that the container should sleep.
   */
  sleep?: IoK8SApiCoreV150;
  /**
   * Deprecated. TCPSocket is NOT supported as a LifecycleHandler and kept for backward compatibility. There is no validation of this field and lifecycle hooks will fail at runtime when it is specified.
   */
  tcpSocket?: IoK8SApiCoreV151;
  [k: string]: unknown;
}
/**
 * ExecAction describes a "run in container" action.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ExecAction".
 */
export interface IoK8SApiCoreV147 {
  /**
   * Command is the command line to execute inside the container, the working directory for the command  is root ('/') in the container's filesystem. The command is simply exec'd, it is not run inside a shell, so traditional shell instructions ('|', etc) won't work. To use a shell, you need to explicitly call out to that shell. Exit status of 0 is treated as live/healthy and non-zero is unhealthy.
   */
  command?: string[];
  [k: string]: unknown;
}
/**
 * HTTPGetAction describes an action based on HTTP Get requests.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.HTTPGetAction".
 */
export interface IoK8SApiCoreV148 {
  /**
   * Host name to connect to, defaults to the pod IP. You probably want to set "Host" in httpHeaders instead.
   */
  host?: string;
  /**
   * Custom headers to set in the request. HTTP allows repeated headers.
   */
  httpHeaders?: IoK8SApiCoreV149[];
  /**
   * Path to access on the HTTP server.
   */
  path?: string;
  /**
   * Name or number of the port to access on the container. Number must be in the range 1 to 65535. Name must be an IANA_SVC_NAME.
   */
  port: IoK8SApimachineryPkgUtilIntstrIntOrString;
  /**
   * Scheme to use for connecting to the host. Defaults to HTTP.
   *
   * Possible enum values:
   *  - `"HTTP"` means that the scheme used will be http://
   *  - `"HTTPS"` means that the scheme used will be https://
   */
  scheme?: "HTTP" | "HTTPS";
  [k: string]: unknown;
}
/**
 * HTTPHeader describes a custom header to be used in HTTP probes
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.HTTPHeader".
 */
export interface IoK8SApiCoreV149 {
  /**
   * The header field name. This will be canonicalized upon output, so case-variant names will be understood as the same header.
   */
  name: string;
  /**
   * The header field value
   */
  value: string;
  [k: string]: unknown;
}
/**
 * SleepAction describes a "sleep" action.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SleepAction".
 */
export interface IoK8SApiCoreV150 {
  /**
   * Seconds is the number of seconds to sleep.
   */
  seconds: number;
  [k: string]: unknown;
}
/**
 * TCPSocketAction describes an action based on opening a socket
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.TCPSocketAction".
 */
export interface IoK8SApiCoreV151 {
  /**
   * Optional: Host name to connect to, defaults to the pod IP.
   */
  host?: string;
  /**
   * Number or name of the port to access on the container. Number must be in the range 1 to 65535. Name must be an IANA_SVC_NAME.
   */
  port: IoK8SApimachineryPkgUtilIntstrIntOrString;
  [k: string]: unknown;
}
/**
 * Probe describes a health check to be performed against a container to determine whether it is alive or ready to receive traffic.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Probe".
 */
export interface IoK8SApiCoreV152 {
  /**
   * Exec specifies a command to execute in the container.
   */
  exec?: IoK8SApiCoreV147;
  /**
   * Minimum consecutive failures for the probe to be considered failed after having succeeded. Defaults to 3. Minimum value is 1.
   */
  failureThreshold?: number;
  /**
   * GRPC specifies a GRPC HealthCheckRequest.
   */
  grpc?: IoK8SApiCoreV153;
  /**
   * HTTPGet specifies an HTTP GET request to perform.
   */
  httpGet?: IoK8SApiCoreV148;
  /**
   * Number of seconds after the container has started before liveness probes are initiated. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#container-probes
   */
  initialDelaySeconds?: number;
  /**
   * How often (in seconds) to perform the probe. Default to 10 seconds. Minimum value is 1.
   */
  periodSeconds?: number;
  /**
   * Minimum consecutive successes for the probe to be considered successful after having failed. Defaults to 1. Must be 1 for liveness and startup. Minimum value is 1.
   */
  successThreshold?: number;
  /**
   * TCPSocket specifies a connection to a TCP port.
   */
  tcpSocket?: IoK8SApiCoreV151;
  /**
   * Optional duration in seconds the pod needs to terminate gracefully upon probe failure. The grace period is the duration in seconds after the processes running in the pod are sent a termination signal and the time when the processes are forcibly halted with a kill signal. Set this value longer than the expected cleanup time for your process. If this value is nil, the pod's terminationGracePeriodSeconds will be used. Otherwise, this value overrides the value provided by the pod spec. Value must be non-negative integer. The value zero indicates stop immediately via the kill signal (no opportunity to shut down). This is a beta field and requires enabling ProbeTerminationGracePeriod feature gate. Minimum value is 1. spec.terminationGracePeriodSeconds is used if unset.
   */
  terminationGracePeriodSeconds?: number;
  /**
   * Number of seconds after which the probe times out. Defaults to 1 second. Minimum value is 1. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#container-probes
   */
  timeoutSeconds?: number;
  [k: string]: unknown;
}
/**
 * GRPCAction specifies an action involving a GRPC service.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.GRPCAction".
 */
export interface IoK8SApiCoreV153 {
  /**
   * Port number of the gRPC service. Number must be in the range 1 to 65535.
   */
  port: number;
  /**
   * Service is the name of the service to place in the gRPC HealthCheckRequest (see https://github.com/grpc/grpc/blob/master/doc/health-checking.md).
   *
   * If this is not specified, the default behavior is defined by gRPC.
   */
  service?: string;
  [k: string]: unknown;
}
/**
 * ContainerPort represents a network port in a single container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerPort".
 */
export interface IoK8SApiCoreV154 {
  /**
   * Number of port to expose on the pod's IP address. This must be a valid port number, 0 < x < 65536.
   */
  containerPort: number;
  /**
   * What host IP to bind the external port to.
   */
  hostIP?: string;
  /**
   * Number of port to expose on the host. If specified, this must be a valid port number, 0 < x < 65536. If HostNetwork is specified, this must match ContainerPort. Most containers do not need this.
   */
  hostPort?: number;
  /**
   * If specified, this must be an IANA_SVC_NAME and unique within the pod. Each named port in a pod must have a unique name. Name for the port that can be referred to by services.
   */
  name?: string;
  /**
   * Protocol for port. Must be UDP, TCP, or SCTP. Defaults to "TCP".
   *
   * Possible enum values:
   *  - `"SCTP"` is the SCTP protocol.
   *  - `"TCP"` is the TCP protocol.
   *  - `"UDP"` is the UDP protocol.
   */
  protocol?: "SCTP" | "TCP" | "UDP";
  [k: string]: unknown;
}
/**
 * ContainerResizePolicy represents resource resize policy for the container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerResizePolicy".
 */
export interface IoK8SApiCoreV155 {
  /**
   * Name of the resource to which this resource resize policy applies. Supported values: cpu, memory.
   */
  resourceName: string;
  /**
   * Restart policy to apply when specified resource is resized. If not specified, it defaults to NotRequired.
   */
  restartPolicy: string;
  [k: string]: unknown;
}
/**
 * ResourceRequirements describes the compute resource requirements.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceRequirements".
 */
export interface IoK8SApiCoreV156 {
  /**
   * Claims lists the names of resources, defined in spec.resourceClaims, that are used by this container.
   *
   * This is an alpha field and requires enabling the DynamicResourceAllocation feature gate.
   *
   * This field is immutable. It can only be set for containers.
   */
  claims?: IoK8SApiCoreV157[];
  /**
   * Limits describes the maximum amount of compute resources allowed. More info: https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
   */
  limits?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * Requests describes the minimum amount of compute resources required. If Requests is omitted for a container, it defaults to Limits if that is explicitly specified, otherwise to an implementation-defined value. Requests cannot exceed Limits. More info: https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
   */
  requests?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  [k: string]: unknown;
}
/**
 * ResourceClaim references one entry in PodSpec.ResourceClaims.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceClaim".
 */
export interface IoK8SApiCoreV157 {
  /**
   * Name must match the name of one entry in pod.spec.resourceClaims of the Pod where this field is used. It makes that resource available inside a container.
   */
  name: string;
  /**
   * Request is the name chosen for a request in the referenced claim. If empty, everything from the claim is made available, otherwise only the result of this request.
   */
  request?: string;
  [k: string]: unknown;
}
/**
 * SecurityContext holds security configuration that will be applied to a container. Some fields are present in both SecurityContext and PodSecurityContext.  When both are set, the values in SecurityContext take precedence.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SecurityContext".
 */
export interface IoK8SApiCoreV158 {
  /**
   * AllowPrivilegeEscalation controls whether a process can gain more privileges than its parent process. This bool directly controls if the no_new_privs flag will be set on the container process. AllowPrivilegeEscalation is true always when the container is: 1) run as Privileged 2) has CAP_SYS_ADMIN Note that this field cannot be set when spec.os.name is windows.
   */
  allowPrivilegeEscalation?: boolean;
  /**
   * appArmorProfile is the AppArmor options to use by this container. If set, this profile overrides the pod's appArmorProfile. Note that this field cannot be set when spec.os.name is windows.
   */
  appArmorProfile?: IoK8SApiCoreV111;
  /**
   * The capabilities to add/drop when running containers. Defaults to the default set of capabilities granted by the container runtime. Note that this field cannot be set when spec.os.name is windows.
   */
  capabilities?: IoK8SApiCoreV121;
  /**
   * Run container in privileged mode. Processes in privileged containers are essentially equivalent to root on the host. Defaults to false. Note that this field cannot be set when spec.os.name is windows.
   */
  privileged?: boolean;
  /**
   * procMount denotes the type of proc mount to use for the containers. The default value is Default which uses the container runtime defaults for readonly paths and masked paths. This requires the ProcMountType feature flag to be enabled. Note that this field cannot be set when spec.os.name is windows.
   *
   * Possible enum values:
   *  - `"Default"` uses the container runtime defaults for readonly and masked paths for /proc. Most container runtimes mask certain paths in /proc to avoid accidental security exposure of special devices or information.
   *  - `"Unmasked"` bypasses the default masking behavior of the container runtime and ensures the newly created /proc the container stays in tact with no modifications.
   */
  procMount?: "Default" | "Unmasked";
  /**
   * Whether this container has a read-only root filesystem. Default is false. Note that this field cannot be set when spec.os.name is windows.
   */
  readOnlyRootFilesystem?: boolean;
  /**
   * The GID to run the entrypoint of the container process. Uses runtime default if unset. May also be set in PodSecurityContext.  If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence. Note that this field cannot be set when spec.os.name is windows.
   */
  runAsGroup?: number;
  /**
   * Indicates that the container must run as a non-root user. If true, the Kubelet will validate the image at runtime to ensure that it does not run as UID 0 (root) and fail to start the container if it does. If unset or false, no such validation will be performed. May also be set in PodSecurityContext.  If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence.
   */
  runAsNonRoot?: boolean;
  /**
   * The UID to run the entrypoint of the container process. Defaults to user specified in image metadata if unspecified. May also be set in PodSecurityContext.  If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence. Note that this field cannot be set when spec.os.name is windows.
   */
  runAsUser?: number;
  /**
   * The SELinux context to be applied to the container. If unspecified, the container runtime will allocate a random SELinux context for each container.  May also be set in PodSecurityContext.  If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence. Note that this field cannot be set when spec.os.name is windows.
   */
  seLinuxOptions?: IoK8SApiCoreV159;
  /**
   * The seccomp options to use by this container. If seccomp options are provided at both the pod & container level, the container options override the pod options. Note that this field cannot be set when spec.os.name is windows.
   */
  seccompProfile?: IoK8SApiCoreV160;
  /**
   * The Windows specific settings applied to all containers. If unspecified, the options from the PodSecurityContext will be used. If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence. Note that this field cannot be set when spec.os.name is linux.
   */
  windowsOptions?: IoK8SApiCoreV161;
  [k: string]: unknown;
}
/**
 * SELinuxOptions are the labels to be applied to the container
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SELinuxOptions".
 */
export interface IoK8SApiCoreV159 {
  /**
   * Level is SELinux level label that applies to the container.
   */
  level?: string;
  /**
   * Role is a SELinux role label that applies to the container.
   */
  role?: string;
  /**
   * Type is a SELinux type label that applies to the container.
   */
  type?: string;
  /**
   * User is a SELinux user label that applies to the container.
   */
  user?: string;
  [k: string]: unknown;
}
/**
 * SeccompProfile defines a pod/container's seccomp profile settings. Only one profile source may be set.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SeccompProfile".
 */
export interface IoK8SApiCoreV160 {
  /**
   * localhostProfile indicates a profile defined in a file on the node should be used. The profile must be preconfigured on the node to work. Must be a descending path, relative to the kubelet's configured seccomp profile location. Must be set if type is "Localhost". Must NOT be set for any other type.
   */
  localhostProfile?: string;
  /**
   * type indicates which kind of seccomp profile will be applied. Valid options are:
   *
   * Localhost - a profile defined in a file on the node should be used. RuntimeDefault - the container runtime default profile should be used. Unconfined - no profile should be applied.
   *
   * Possible enum values:
   *  - `"Localhost"` indicates a profile defined in a file on the node should be used. The file's location relative to <kubelet-root-dir>/seccomp.
   *  - `"RuntimeDefault"` represents the default container runtime seccomp profile.
   *  - `"Unconfined"` indicates no seccomp profile is applied (A.K.A. unconfined).
   */
  type: "Localhost" | "RuntimeDefault" | "Unconfined";
  [k: string]: unknown;
}
/**
 * WindowsSecurityContextOptions contain Windows-specific options and credentials.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.WindowsSecurityContextOptions".
 */
export interface IoK8SApiCoreV161 {
  /**
   * GMSACredentialSpec is where the GMSA admission webhook (https://github.com/kubernetes-sigs/windows-gmsa) inlines the contents of the GMSA credential spec named by the GMSACredentialSpecName field.
   */
  gmsaCredentialSpec?: string;
  /**
   * GMSACredentialSpecName is the name of the GMSA credential spec to use.
   */
  gmsaCredentialSpecName?: string;
  /**
   * HostProcess determines if a container should be run as a 'Host Process' container. All of a Pod's containers must have the same effective HostProcess value (it is not allowed to have a mix of HostProcess containers and non-HostProcess containers). In addition, if HostProcess is true then HostNetwork must also be set to true.
   */
  hostProcess?: boolean;
  /**
   * The UserName in Windows to run the entrypoint of the container process. Defaults to the user specified in image metadata if unspecified. May also be set in PodSecurityContext. If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence.
   */
  runAsUserName?: string;
  [k: string]: unknown;
}
/**
 * volumeDevice describes a mapping of a raw block device within a container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.VolumeDevice".
 */
export interface IoK8SApiCoreV162 {
  /**
   * devicePath is the path inside of the container that the device will be mapped to.
   */
  devicePath: string;
  /**
   * name must match the name of a persistentVolumeClaim in the pod
   */
  name: string;
  [k: string]: unknown;
}
/**
 * VolumeMount describes a mounting of a Volume within a container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.VolumeMount".
 */
export interface IoK8SApiCoreV163 {
  /**
   * Path within the container at which the volume should be mounted.  Must not contain ':'.
   */
  mountPath: string;
  /**
   * mountPropagation determines how mounts are propagated from the host to container and the other way around. When not set, MountPropagationNone is used. This field is beta in 1.10. When RecursiveReadOnly is set to IfPossible or to Enabled, MountPropagation must be None or unspecified (which defaults to None).
   *
   * Possible enum values:
   *  - `"Bidirectional"` means that the volume in a container will receive new mounts from the host or other containers, and its own mounts will be propagated from the container to the host or other containers. Note that this mode is recursively applied to all mounts in the volume ("rshared" in Linux terminology).
   *  - `"HostToContainer"` means that the volume in a container will receive new mounts from the host or other containers, but filesystems mounted inside the container won't be propagated to the host or other containers. Note that this mode is recursively applied to all mounts in the volume ("rslave" in Linux terminology).
   *  - `"None"` means that the volume in a container will not receive new mounts from the host or other containers, and filesystems mounted inside the container won't be propagated to the host or other containers. Note that this mode corresponds to "private" in Linux terminology.
   */
  mountPropagation?: "Bidirectional" | "HostToContainer" | "None";
  /**
   * This must match the Name of a Volume.
   */
  name: string;
  /**
   * Mounted read-only if true, read-write otherwise (false or unspecified). Defaults to false.
   */
  readOnly?: boolean;
  /**
   * RecursiveReadOnly specifies whether read-only mounts should be handled recursively.
   *
   * If ReadOnly is false, this field has no meaning and must be unspecified.
   *
   * If ReadOnly is true, and this field is set to Disabled, the mount is not made recursively read-only.  If this field is set to IfPossible, the mount is made recursively read-only, if it is supported by the container runtime.  If this field is set to Enabled, the mount is made recursively read-only if it is supported by the container runtime, otherwise the pod will not be started and an error will be generated to indicate the reason.
   *
   * If this field is set to IfPossible or Enabled, MountPropagation must be set to None (or be unspecified, which defaults to None).
   *
   * If this field is not specified, it is treated as an equivalent of Disabled.
   */
  recursiveReadOnly?: string;
  /**
   * Path within the volume from which the container's volume should be mounted. Defaults to "" (volume's root).
   */
  subPath?: string;
  /**
   * Expanded path within the volume from which the container's volume should be mounted. Behaves similarly to SubPath but environment variable references $(VAR_NAME) are expanded using the container's environment. Defaults to "" (volume's root). SubPathExpr and SubPath are mutually exclusive.
   */
  subPathExpr?: string;
  [k: string]: unknown;
}
/**
 * Describe a container image
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerImage".
 */
export interface IoK8SApiCoreV164 {
  /**
   * Names by which this image is known. e.g. ["kubernetes.example/hyperkube:v1.0.7", "cloud-vendor.registry.example/cloud-vendor/hyperkube:v1.0.7"]
   */
  names?: string[];
  /**
   * The size of the image in bytes.
   */
  sizeBytes?: number;
  [k: string]: unknown;
}
/**
 * ContainerState holds a possible state of container. Only one of its members may be specified. If none of them is specified, the default one is ContainerStateWaiting.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerState".
 */
export interface IoK8SApiCoreV165 {
  /**
   * Details about a running container
   */
  running?: IoK8SApiCoreV166;
  /**
   * Details about a terminated container
   */
  terminated?: IoK8SApiCoreV167;
  /**
   * Details about a waiting container
   */
  waiting?: IoK8SApiCoreV168;
  [k: string]: unknown;
}
/**
 * ContainerStateRunning is a running state of a container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerStateRunning".
 */
export interface IoK8SApiCoreV166 {
  /**
   * Time at which the container was last (re-)started
   */
  startedAt?: IoK8SApimachineryPkgApisMetaV1Time;
  [k: string]: unknown;
}
/**
 * ContainerStateTerminated is a terminated state of a container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerStateTerminated".
 */
export interface IoK8SApiCoreV167 {
  /**
   * Container's ID in the format '<type>://<container_id>'
   */
  containerID?: string;
  /**
   * Exit status from the last termination of the container
   */
  exitCode: number;
  /**
   * Time at which the container last terminated
   */
  finishedAt?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Message regarding the last termination of the container
   */
  message?: string;
  /**
   * (brief) reason from the last termination of the container
   */
  reason?: string;
  /**
   * Signal from the last termination of the container
   */
  signal?: number;
  /**
   * Time at which previous execution of the container started
   */
  startedAt?: IoK8SApimachineryPkgApisMetaV1Time;
  [k: string]: unknown;
}
/**
 * ContainerStateWaiting is a waiting state of a container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerStateWaiting".
 */
export interface IoK8SApiCoreV168 {
  /**
   * Message regarding why the container is not yet running.
   */
  message?: string;
  /**
   * (brief) reason the container is not yet running.
   */
  reason?: string;
  [k: string]: unknown;
}
/**
 * ContainerStatus contains details for the current status of this container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerStatus".
 */
export interface IoK8SApiCoreV169 {
  /**
   * AllocatedResources represents the compute resources allocated for this container by the node. Kubelet sets this value to Container.Resources.Requests upon successful pod admission and after successfully admitting desired pod resize.
   */
  allocatedResources?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * AllocatedResourcesStatus represents the status of various resources allocated for this Pod.
   */
  allocatedResourcesStatus?: IoK8SApiCoreV170[];
  /**
   * ContainerID is the ID of the container in the format '<type>://<container_id>'. Where type is a container runtime identifier, returned from Version call of CRI API (for example "containerd").
   */
  containerID?: string;
  /**
   * Image is the name of container image that the container is running. The container image may not match the image used in the PodSpec, as it may have been resolved by the runtime. More info: https://kubernetes.io/docs/concepts/containers/images.
   */
  image: string;
  /**
   * ImageID is the image ID of the container's image. The image ID may not match the image ID of the image used in the PodSpec, as it may have been resolved by the runtime.
   */
  imageID: string;
  /**
   * LastTerminationState holds the last termination state of the container to help debug container crashes and restarts. This field is not populated if the container is still running and RestartCount is 0.
   */
  lastState?: IoK8SApiCoreV165;
  /**
   * Name is a DNS_LABEL representing the unique name of the container. Each container in a pod must have a unique name across all container types. Cannot be updated.
   */
  name: string;
  /**
   * Ready specifies whether the container is currently passing its readiness check. The value will change as readiness probes keep executing. If no readiness probes are specified, this field defaults to true once the container is fully started (see Started field).
   *
   * The value is typically used to determine whether a container is ready to accept traffic.
   */
  ready: boolean;
  /**
   * Resources represents the compute resource requests and limits that have been successfully enacted on the running container after it has been started or has been successfully resized.
   */
  resources?: IoK8SApiCoreV156;
  /**
   * RestartCount holds the number of times the container has been restarted. Kubelet makes an effort to always increment the value, but there are cases when the state may be lost due to node restarts and then the value may be reset to 0. The value is never negative.
   */
  restartCount: number;
  /**
   * Started indicates whether the container has finished its postStart lifecycle hook and passed its startup probe. Initialized as false, becomes true after startupProbe is considered successful. Resets to false when the container is restarted, or if kubelet loses state temporarily. In both cases, startup probes will run again. Is always true when no startupProbe is defined and container is running and has passed the postStart lifecycle hook. The null value must be treated the same as false.
   */
  started?: boolean;
  /**
   * State holds details about the container's current condition.
   */
  state?: IoK8SApiCoreV165;
  /**
   * User represents user identity information initially attached to the first process of the container
   */
  user?: IoK8SApiCoreV172;
  /**
   * Status of volume mounts.
   */
  volumeMounts?: IoK8SApiCoreV174[];
  [k: string]: unknown;
}
/**
 * ResourceStatus represents the status of a single resource allocated to a Pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceStatus".
 */
export interface IoK8SApiCoreV170 {
  /**
   * Name of the resource. Must be unique within the pod and in case of non-DRA resource, match one of the resources from the pod spec. For DRA resources, the value must be "claim:<claim_name>/<request>". When this status is reported about a container, the "claim_name" and "request" must match one of the claims of this container.
   */
  name: string;
  /**
   * List of unique resources health. Each element in the list contains an unique resource ID and its health. At a minimum, for the lifetime of a Pod, resource ID must uniquely identify the resource allocated to the Pod on the Node. If other Pod on the same Node reports the status with the same resource ID, it must be the same resource they share. See ResourceID type definition for a specific format it has in various use cases.
   */
  resources?: IoK8SApiCoreV171[];
  [k: string]: unknown;
}
/**
 * ResourceHealth represents the health of a resource. It has the latest device health information. This is a part of KEP https://kep.k8s.io/4680.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceHealth".
 */
export interface IoK8SApiCoreV171 {
  /**
   * Health of the resource. can be one of:
   *  - Healthy: operates as normal
   *  - Unhealthy: reported unhealthy. We consider this a temporary health issue
   *               since we do not have a mechanism today to distinguish
   *               temporary and permanent issues.
   *  - Unknown: The status cannot be determined.
   *             For example, Device Plugin got unregistered and hasn't been re-registered since.
   *
   * In future we may want to introduce the PermanentlyUnhealthy Status.
   */
  health?: string;
  /**
   * ResourceID is the unique identifier of the resource. See the ResourceID type for more information.
   */
  resourceID: string;
  [k: string]: unknown;
}
/**
 * ContainerUser represents user identity information
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ContainerUser".
 */
export interface IoK8SApiCoreV172 {
  /**
   * Linux holds user identity information initially attached to the first process of the containers in Linux. Note that the actual running identity can be changed if the process has enough privilege to do so.
   */
  linux?: IoK8SApiCoreV173;
  [k: string]: unknown;
}
/**
 * LinuxContainerUser represents user identity information in Linux containers
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LinuxContainerUser".
 */
export interface IoK8SApiCoreV173 {
  /**
   * GID is the primary gid initially attached to the first process in the container
   */
  gid: number;
  /**
   * SupplementalGroups are the supplemental groups initially attached to the first process in the container
   */
  supplementalGroups?: number[];
  /**
   * UID is the primary uid initially attached to the first process in the container
   */
  uid: number;
  [k: string]: unknown;
}
/**
 * VolumeMountStatus shows status of volume mounts.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.VolumeMountStatus".
 */
export interface IoK8SApiCoreV174 {
  /**
   * MountPath corresponds to the original VolumeMount.
   */
  mountPath: string;
  /**
   * Name corresponds to the name of the original VolumeMount.
   */
  name: string;
  /**
   * ReadOnly corresponds to the original VolumeMount.
   */
  readOnly?: boolean;
  /**
   * RecursiveReadOnly must be set to Disabled, Enabled, or unspecified (for non-readonly mounts). An IfPossible value in the original VolumeMount must be translated to Disabled or Enabled, depending on the mount result.
   */
  recursiveReadOnly?: string;
  [k: string]: unknown;
}
/**
 * DaemonEndpoint contains information about a single Daemon endpoint.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.DaemonEndpoint".
 */
export interface IoK8SApiCoreV175 {
  /**
   * Port number of the given endpoint.
   */
  Port: number;
  [k: string]: unknown;
}
/**
 * Represents downward API info for projecting into a projected volume. Note that this is identical to a downwardAPI volume source without the default mode.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.DownwardAPIProjection".
 */
export interface IoK8SApiCoreV176 {
  /**
   * Items is a list of DownwardAPIVolume file
   */
  items?: IoK8SApiCoreV177[];
  [k: string]: unknown;
}
/**
 * DownwardAPIVolumeFile represents information to create the file containing the pod field
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.DownwardAPIVolumeFile".
 */
export interface IoK8SApiCoreV177 {
  /**
   * Required: Selects a field of the pod: only annotations, labels, name, namespace and uid are supported.
   */
  fieldRef?: IoK8SApiCoreV140;
  /**
   * Optional: mode bits used to set permissions on this file, must be an octal value between 0000 and 0777 or a decimal value between 0 and 511. YAML accepts both octal and decimal values, JSON requires decimal values for mode bits. If not specified, the volume defaultMode will be used. This might be in conflict with other options that affect the file mode, like fsGroup, and the result can be other mode bits set.
   */
  mode?: number;
  /**
   * Required: Path is  the relative path name of the file to be created. Must not be absolute or contain the '..' path. Must be utf-8 encoded. The first item of the relative path must not start with '..'
   */
  path: string;
  /**
   * Selects a resource of the container: only resources limits and requests (limits.cpu, limits.memory, requests.cpu and requests.memory) are currently supported.
   */
  resourceFieldRef?: IoK8SApiCoreV141;
  [k: string]: unknown;
}
/**
 * DownwardAPIVolumeSource represents a volume containing downward API info. Downward API volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.DownwardAPIVolumeSource".
 */
export interface IoK8SApiCoreV178 {
  /**
   * Optional: mode bits to use on created files by default. Must be a Optional: mode bits used to set permissions on created files by default. Must be an octal value between 0000 and 0777 or a decimal value between 0 and 511. YAML accepts both octal and decimal values, JSON requires decimal values for mode bits. Defaults to 0644. Directories within the path are not affected by this setting. This might be in conflict with other options that affect the file mode, like fsGroup, and the result can be other mode bits set.
   */
  defaultMode?: number;
  /**
   * Items is a list of downward API volume file
   */
  items?: IoK8SApiCoreV177[];
  [k: string]: unknown;
}
/**
 * Represents an empty directory for a pod. Empty directory volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EmptyDirVolumeSource".
 */
export interface IoK8SApiCoreV179 {
  /**
   * medium represents what type of storage medium should back this directory. The default is "" which means to use the node's default medium. Must be an empty string (default) or Memory. More info: https://kubernetes.io/docs/concepts/storage/volumes#emptydir
   */
  medium?: string;
  /**
   * sizeLimit is the total amount of local storage required for this EmptyDir volume. The size limit is also applicable for memory medium. The maximum usage on memory medium EmptyDir would be the minimum value between the SizeLimit specified here and the sum of memory limits of all containers in a pod. The default is nil which means that the limit is undefined. More info: https://kubernetes.io/docs/concepts/storage/volumes#emptydir
   */
  sizeLimit?: IoK8SApimachineryPkgApiResourceQuantity;
  [k: string]: unknown;
}
/**
 * EndpointAddress is a tuple that describes single IP address.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EndpointAddress".
 */
export interface IoK8SApiCoreV180 {
  /**
   * The Hostname of this endpoint
   */
  hostname?: string;
  /**
   * The IP of this endpoint. May not be loopback (127.0.0.0/8 or ::1), link-local (169.254.0.0/16 or fe80::/10), or link-local multicast (224.0.0.0/24 or ff02::/16).
   */
  ip: string;
  /**
   * Optional: Node hosting this endpoint. This can be used to determine endpoints local to a node.
   */
  nodeName?: string;
  /**
   * Reference to object providing the endpoint.
   */
  targetRef?: IoK8SApiCoreV116;
  [k: string]: unknown;
}
/**
 * EndpointPort is a tuple that describes a single port.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EndpointPort".
 */
export interface IoK8SApiCoreV181 {
  /**
   * The application protocol for this port. This is used as a hint for implementations to offer richer behavior for protocols that they understand. This field follows standard Kubernetes label syntax. Valid values are either:
   *
   * * Un-prefixed protocol names - reserved for IANA standard service names (as per RFC-6335 and https://www.iana.org/assignments/service-names).
   *
   * * Kubernetes-defined prefixed names:
   *   * 'kubernetes.io/h2c' - HTTP/2 prior knowledge over cleartext as described in https://www.rfc-editor.org/rfc/rfc9113.html#name-starting-http-2-with-prior-
   *   * 'kubernetes.io/ws'  - WebSocket over cleartext as described in https://www.rfc-editor.org/rfc/rfc6455
   *   * 'kubernetes.io/wss' - WebSocket over TLS as described in https://www.rfc-editor.org/rfc/rfc6455
   *
   * * Other protocols should use implementation-defined prefixed names such as mycompany.com/my-custom-protocol.
   */
  appProtocol?: string;
  /**
   * The name of this port.  This must match the 'name' field in the corresponding ServicePort. Must be a DNS_LABEL. Optional only if one port is defined.
   */
  name?: string;
  /**
   * The port number of the endpoint.
   */
  port: number;
  /**
   * The IP protocol for this port. Must be UDP, TCP, or SCTP. Default is TCP.
   *
   * Possible enum values:
   *  - `"SCTP"` is the SCTP protocol.
   *  - `"TCP"` is the TCP protocol.
   *  - `"UDP"` is the UDP protocol.
   */
  protocol?: "SCTP" | "TCP" | "UDP";
  [k: string]: unknown;
}
/**
 * EndpointSubset is a group of addresses with a common set of ports. The expanded set of endpoints is the Cartesian product of Addresses x Ports. For example, given:
 *
 * 	{
 * 	  Addresses: [{"ip": "10.10.1.1"}, {"ip": "10.10.2.2"}],
 * 	  Ports:     [{"name": "a", "port": 8675}, {"name": "b", "port": 309}]
 * 	}
 *
 * The resulting set of endpoints can be viewed as:
 *
 * 	a: [ 10.10.1.1:8675, 10.10.2.2:8675 ],
 * 	b: [ 10.10.1.1:309, 10.10.2.2:309 ]
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EndpointSubset".
 */
export interface IoK8SApiCoreV182 {
  /**
   * IP addresses which offer the related ports that are marked as ready. These endpoints should be considered safe for load balancers and clients to utilize.
   */
  addresses?: IoK8SApiCoreV180[];
  /**
   * IP addresses which offer the related ports but are not currently marked as ready because they have not yet finished starting, have recently failed a readiness check, or have recently failed a liveness check.
   */
  notReadyAddresses?: IoK8SApiCoreV180[];
  /**
   * Port numbers available on the related IP addresses.
   */
  ports?: IoK8SApiCoreV181[];
  [k: string]: unknown;
}
/**
 * Endpoints is a collection of endpoints that implement the actual service. Example:
 *
 * 	 Name: "mysvc",
 * 	 Subsets: [
 * 	   {
 * 	     Addresses: [{"ip": "10.10.1.1"}, {"ip": "10.10.2.2"}],
 * 	     Ports: [{"name": "a", "port": 8675}, {"name": "b", "port": 309}]
 * 	   },
 * 	   {
 * 	     Addresses: [{"ip": "10.10.3.3"}],
 * 	     Ports: [{"name": "a", "port": 93}, {"name": "b", "port": 76}]
 * 	   },
 * 	]
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Endpoints".
 */
export interface IoK8SApiCoreV183 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * The set of all endpoints is the union of all subsets. Addresses are placed into subsets according to the IPs they share. A single address with multiple ports, some of which are ready and some of which are not (because they come from different containers) will result in the address being displayed in different subsets for the different ports. No address will appear in both Addresses and NotReadyAddresses in the same subset. Sets of addresses and ports that comprise a service.
   */
  subsets?: IoK8SApiCoreV182[];
  [k: string]: unknown;
}
/**
 * EndpointsList is a list of endpoints.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EndpointsList".
 */
export interface IoK8SApiCoreV1EndpointsList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of endpoints.
   */
  items: IoK8SApiCoreV183[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * An EphemeralContainer is a temporary container that you may add to an existing Pod for user-initiated activities such as debugging. Ephemeral containers have no resource or scheduling guarantees, and they will not be restarted when they exit or when a Pod is removed or restarted. The kubelet may evict a Pod if an ephemeral container causes the Pod to exceed its resource allocation.
 *
 * To add an ephemeral container, use the ephemeralcontainers subresource of an existing Pod. Ephemeral containers may not be removed or restarted.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EphemeralContainer".
 */
export interface IoK8SApiCoreV184 {
  /**
   * Arguments to the entrypoint. The image's CMD is used if this is not provided. Variable references $(VAR_NAME) are expanded using the container's environment. If a variable cannot be resolved, the reference in the input string will be unchanged. Double $$ are reduced to a single $, which allows for escaping the $(VAR_NAME) syntax: i.e. "$$(VAR_NAME)" will produce the string literal "$(VAR_NAME)". Escaped references will never be expanded, regardless of whether the variable exists or not. Cannot be updated. More info: https://kubernetes.io/docs/tasks/inject-data-application/define-command-argument-container/#running-a-command-in-a-shell
   */
  args?: string[];
  /**
   * Entrypoint array. Not executed within a shell. The image's ENTRYPOINT is used if this is not provided. Variable references $(VAR_NAME) are expanded using the container's environment. If a variable cannot be resolved, the reference in the input string will be unchanged. Double $$ are reduced to a single $, which allows for escaping the $(VAR_NAME) syntax: i.e. "$$(VAR_NAME)" will produce the string literal "$(VAR_NAME)". Escaped references will never be expanded, regardless of whether the variable exists or not. Cannot be updated. More info: https://kubernetes.io/docs/tasks/inject-data-application/define-command-argument-container/#running-a-command-in-a-shell
   */
  command?: string[];
  /**
   * List of environment variables to set in the container. Cannot be updated.
   */
  env?: IoK8SApiCoreV138[];
  /**
   * List of sources to populate environment variables in the container. The keys defined within a source must be a C_IDENTIFIER. All invalid keys will be reported as an event when the container is starting. When a key exists in multiple sources, the value associated with the last source will take precedence. Values defined by an Env with a duplicate key will take precedence. Cannot be updated.
   */
  envFrom?: IoK8SApiCoreV143[];
  /**
   * Container image name. More info: https://kubernetes.io/docs/concepts/containers/images
   */
  image?: string;
  /**
   * Image pull policy. One of Always, Never, IfNotPresent. Defaults to Always if :latest tag is specified, or IfNotPresent otherwise. Cannot be updated. More info: https://kubernetes.io/docs/concepts/containers/images#updating-images
   *
   * Possible enum values:
   *  - `"Always"` means that kubelet always attempts to pull the latest image. Container will fail If the pull fails.
   *  - `"IfNotPresent"` means that kubelet pulls if the image isn't present on disk. Container will fail if the image isn't present and the pull fails.
   *  - `"Never"` means that kubelet never pulls an image, but only uses a local image. Container will fail if the image isn't present
   */
  imagePullPolicy?: "Always" | "IfNotPresent" | "Never";
  /**
   * Lifecycle is not allowed for ephemeral containers.
   */
  lifecycle?: IoK8SApiCoreV145;
  /**
   * Probes are not allowed for ephemeral containers.
   */
  livenessProbe?: IoK8SApiCoreV152;
  /**
   * Name of the ephemeral container specified as a DNS_LABEL. This name must be unique among all containers, init containers and ephemeral containers.
   */
  name: string;
  /**
   * Ports are not allowed for ephemeral containers.
   */
  ports?: IoK8SApiCoreV154[];
  /**
   * Probes are not allowed for ephemeral containers.
   */
  readinessProbe?: IoK8SApiCoreV152;
  /**
   * Resources resize policy for the container.
   */
  resizePolicy?: IoK8SApiCoreV155[];
  /**
   * Resources are not allowed for ephemeral containers. Ephemeral containers use spare resources already allocated to the pod.
   */
  resources?: IoK8SApiCoreV156;
  /**
   * Restart policy for the container to manage the restart behavior of each container within a pod. This may only be set for init containers. You cannot set this field on ephemeral containers.
   */
  restartPolicy?: string;
  /**
   * Optional: SecurityContext defines the security options the ephemeral container should be run with. If set, the fields of SecurityContext override the equivalent fields of PodSecurityContext.
   */
  securityContext?: IoK8SApiCoreV158;
  /**
   * Probes are not allowed for ephemeral containers.
   */
  startupProbe?: IoK8SApiCoreV152;
  /**
   * Whether this container should allocate a buffer for stdin in the container runtime. If this is not set, reads from stdin in the container will always result in EOF. Default is false.
   */
  stdin?: boolean;
  /**
   * Whether the container runtime should close the stdin channel after it has been opened by a single attach. When stdin is true the stdin stream will remain open across multiple attach sessions. If stdinOnce is set to true, stdin is opened on container start, is empty until the first client attaches to stdin, and then remains open and accepts data until the client disconnects, at which time stdin is closed and remains closed until the container is restarted. If this flag is false, a container processes that reads from stdin will never receive an EOF. Default is false
   */
  stdinOnce?: boolean;
  /**
   * If set, the name of the container from PodSpec that this ephemeral container targets. The ephemeral container will be run in the namespaces (IPC, PID, etc) of this container. If not set then the ephemeral container uses the namespaces configured in the Pod spec.
   *
   * The container runtime must implement support for this feature. If the runtime does not support namespace targeting then the result of setting this field is undefined.
   */
  targetContainerName?: string;
  /**
   * Optional: Path at which the file to which the container's termination message will be written is mounted into the container's filesystem. Message written is intended to be brief final status, such as an assertion failure message. Will be truncated by the node if greater than 4096 bytes. The total message length across all containers will be limited to 12kb. Defaults to /dev/termination-log. Cannot be updated.
   */
  terminationMessagePath?: string;
  /**
   * Indicate how the termination message should be populated. File will use the contents of terminationMessagePath to populate the container status message on both success and failure. FallbackToLogsOnError will use the last chunk of container log output if the termination message file is empty and the container exited with an error. The log output is limited to 2048 bytes or 80 lines, whichever is smaller. Defaults to File. Cannot be updated.
   *
   * Possible enum values:
   *  - `"FallbackToLogsOnError"` will read the most recent contents of the container logs for the container status message when the container exits with an error and the terminationMessagePath has no contents.
   *  - `"File"` is the default behavior and will set the container status message to the contents of the container's terminationMessagePath when the container exits.
   */
  terminationMessagePolicy?: "FallbackToLogsOnError" | "File";
  /**
   * Whether this container should allocate a TTY for itself, also requires 'stdin' to be true. Default is false.
   */
  tty?: boolean;
  /**
   * volumeDevices is the list of block devices to be used by the container.
   */
  volumeDevices?: IoK8SApiCoreV162[];
  /**
   * Pod volumes to mount into the container's filesystem. Subpath mounts are not allowed for ephemeral containers. Cannot be updated.
   */
  volumeMounts?: IoK8SApiCoreV163[];
  /**
   * Container's working directory. If not specified, the container runtime's default will be used, which might be configured in the container image. Cannot be updated.
   */
  workingDir?: string;
  [k: string]: unknown;
}
/**
 * Represents an ephemeral volume that is handled by a normal storage driver.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EphemeralVolumeSource".
 */
export interface IoK8SApiCoreV185 {
  /**
   * Will be used to create a stand-alone PVC to provision the volume. The pod in which this EphemeralVolumeSource is embedded will be the owner of the PVC, i.e. the PVC will be deleted together with the pod.  The name of the PVC will be `<pod name>-<volume name>` where `<volume name>` is the name from the `PodSpec.Volumes` array entry. Pod validation will reject the pod if the concatenated name is not valid for a PVC (for example, too long).
   *
   * An existing PVC with that name that is not owned by the pod will *not* be used for the pod to avoid using an unrelated volume by mistake. Starting the pod is then blocked until the unrelated PVC is removed. If such a pre-created PVC is meant to be used by the pod, the PVC has to updated with an owner reference to the pod once the pod exists. Normally this should not be necessary, but it may be useful when manually reconstructing a broken cluster.
   *
   * This field is read-only and no changes will be made by Kubernetes to the PVC after it has been created.
   *
   * Required, must not be nil.
   */
  volumeClaimTemplate?: IoK8SApiCoreV186;
  [k: string]: unknown;
}
/**
 * PersistentVolumeClaimTemplate is used to produce PersistentVolumeClaim objects as part of an EphemeralVolumeSource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeClaimTemplate".
 */
export interface IoK8SApiCoreV186 {
  /**
   * May contain labels and annotations that will be copied into the PVC when creating it. No other fields are allowed and will be rejected during validation.
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * The specification for the PersistentVolumeClaim. The entire content is copied unchanged into the PVC that gets created from this template. The same fields as in a PersistentVolumeClaim are also valid here.
   */
  spec: IoK8SApiCoreV187;
  [k: string]: unknown;
}
/**
 * PersistentVolumeClaimSpec describes the common attributes of storage devices and allows a Source for provider-specific attributes
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeClaimSpec".
 */
export interface IoK8SApiCoreV187 {
  /**
   * accessModes contains the desired access modes the volume should have. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#access-modes-1
   */
  accessModes?: ("ReadOnlyMany" | "ReadWriteMany" | "ReadWriteOnce" | "ReadWriteOncePod")[];
  /**
   * dataSource field can be used to specify either: * An existing VolumeSnapshot object (snapshot.storage.k8s.io/VolumeSnapshot) * An existing PVC (PersistentVolumeClaim) If the provisioner or an external controller can support the specified data source, it will create a new volume based on the contents of the specified data source. When the AnyVolumeDataSource feature gate is enabled, dataSource contents will be copied to dataSourceRef, and dataSourceRef contents will be copied to dataSource when dataSourceRef.namespace is not specified. If the namespace is specified, then dataSourceRef will not be copied to dataSource.
   */
  dataSource?: IoK8SApiCoreV188;
  /**
   * dataSourceRef specifies the object from which to populate the volume with data, if a non-empty volume is desired. This may be any object from a non-empty API group (non core object) or a PersistentVolumeClaim object. When this field is specified, volume binding will only succeed if the type of the specified object matches some installed volume populator or dynamic provisioner. This field will replace the functionality of the dataSource field and as such if both fields are non-empty, they must have the same value. For backwards compatibility, when namespace isn't specified in dataSourceRef, both fields (dataSource and dataSourceRef) will be set to the same value automatically if one of them is empty and the other is non-empty. When namespace is specified in dataSourceRef, dataSource isn't set to the same value and must be empty. There are three important differences between dataSource and dataSourceRef: * While dataSource only allows two specific types of objects, dataSourceRef
   *   allows any non-core object, as well as PersistentVolumeClaim objects.
   * * While dataSource ignores disallowed values (dropping them), dataSourceRef
   *   preserves all values, and generates an error if a disallowed value is
   *   specified.
   * * While dataSource only allows local objects, dataSourceRef allows objects
   *   in any namespaces.
   * (Beta) Using this field requires the AnyVolumeDataSource feature gate to be enabled. (Alpha) Using the namespace field of dataSourceRef requires the CrossNamespaceVolumeDataSource feature gate to be enabled.
   */
  dataSourceRef?: IoK8SApiCoreV189;
  /**
   * resources represents the minimum resources the volume should have. If RecoverVolumeExpansionFailure feature is enabled users are allowed to specify resource requirements that are lower than previous value but must still be higher than capacity recorded in the status field of the claim. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#resources
   */
  resources?: IoK8SApiCoreV190;
  /**
   * selector is a label query over volumes to consider for binding.
   */
  selector?: IoK8SApimachineryPkgApisMetaV14;
  /**
   * storageClassName is the name of the StorageClass required by the claim. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#class-1
   */
  storageClassName?: string;
  /**
   * volumeAttributesClassName may be used to set the VolumeAttributesClass used by this claim. If specified, the CSI driver will create or update the volume with the attributes defined in the corresponding VolumeAttributesClass. This has a different purpose than storageClassName, it can be changed after the claim is created. An empty string value means that no VolumeAttributesClass will be applied to the claim but it's not allowed to reset this field to empty string once it is set. If unspecified and the PersistentVolumeClaim is unbound, the default VolumeAttributesClass will be set by the persistentvolume controller if it exists. If the resource referred to by volumeAttributesClass does not exist, this PersistentVolumeClaim will be set to a Pending state, as reflected by the modifyVolumeStatus field, until such as a resource exists. More info: https://kubernetes.io/docs/concepts/storage/volume-attributes-classes/ (Beta) Using this field requires the VolumeAttributesClass feature gate to be enabled (off by default).
   */
  volumeAttributesClassName?: string;
  /**
   * volumeMode defines what type of volume is required by the claim. Value of Filesystem is implied when not included in claim spec.
   *
   * Possible enum values:
   *  - `"Block"` means the volume will not be formatted with a filesystem and will remain a raw block device.
   *  - `"Filesystem"` means the volume will be or is formatted with a filesystem.
   */
  volumeMode?: "Block" | "Filesystem";
  /**
   * volumeName is the binding reference to the PersistentVolume backing this claim.
   */
  volumeName?: string;
  [k: string]: unknown;
}
/**
 * TypedLocalObjectReference contains enough information to let you locate the typed referenced object inside the same namespace.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.TypedLocalObjectReference".
 */
export interface IoK8SApiCoreV188 {
  /**
   * APIGroup is the group for the resource being referenced. If APIGroup is not specified, the specified Kind must be in the core API group. For any other third-party types, APIGroup is required.
   */
  apiGroup?: string;
  /**
   * Kind is the type of resource being referenced
   */
  kind: string;
  /**
   * Name is the name of resource being referenced
   */
  name: string;
  [k: string]: unknown;
}
/**
 * TypedObjectReference contains enough information to let you locate the typed referenced object
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.TypedObjectReference".
 */
export interface IoK8SApiCoreV189 {
  /**
   * APIGroup is the group for the resource being referenced. If APIGroup is not specified, the specified Kind must be in the core API group. For any other third-party types, APIGroup is required.
   */
  apiGroup?: string;
  /**
   * Kind is the type of resource being referenced
   */
  kind: string;
  /**
   * Name is the name of resource being referenced
   */
  name: string;
  /**
   * Namespace is the namespace of resource being referenced Note that when a namespace is specified, a gateway.networking.k8s.io/ReferenceGrant object is required in the referent namespace to allow that namespace's owner to accept the reference. See the ReferenceGrant documentation for details. (Alpha) This field requires the CrossNamespaceVolumeDataSource feature gate to be enabled.
   */
  namespace?: string;
  [k: string]: unknown;
}
/**
 * VolumeResourceRequirements describes the storage resource requirements for a volume.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.VolumeResourceRequirements".
 */
export interface IoK8SApiCoreV190 {
  /**
   * Limits describes the maximum amount of compute resources allowed. More info: https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
   */
  limits?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * Requests describes the minimum amount of compute resources required. If Requests is omitted for a container, it defaults to Limits if that is explicitly specified, otherwise to an implementation-defined value. Requests cannot exceed Limits. More info: https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
   */
  requests?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  [k: string]: unknown;
}
/**
 * Event is a report of an event somewhere in the cluster.  Events have a limited retention time and triggers and messages may evolve with time.  Event consumers should not rely on the timing of an event with a given Reason reflecting a consistent underlying trigger, or the continued existence of events with that Reason.  Events should be treated as informative, best-effort, supplemental data.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Event".
 */
export interface IoK8SApiCoreV191 {
  /**
   * What action was taken/failed regarding to the Regarding object.
   */
  action?: string;
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * The number of times this event has occurred.
   */
  count?: number;
  /**
   * Time when this Event was first observed.
   */
  eventTime?: IoK8SApimachineryPkgApisMetaV1MicroTime;
  /**
   * The time at which the event was first recorded. (Time of server receipt is in TypeMeta.)
   */
  firstTimestamp?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * The object that this event is about.
   */
  involvedObject: IoK8SApiCoreV116;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * The time at which the most recent occurrence of this event was recorded.
   */
  lastTimestamp?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * A human-readable description of the status of this operation.
   */
  message?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata: IoK8SApimachineryPkgApisMetaV1;
  /**
   * This should be a short, machine understandable string that gives the reason for the transition into the object's current status.
   */
  reason?: string;
  /**
   * Optional secondary object for more complex actions.
   */
  related?: IoK8SApiCoreV116;
  /**
   * Name of the controller that emitted this Event, e.g. `kubernetes.io/kubelet`.
   */
  reportingComponent?: string;
  /**
   * ID of the controller instance, e.g. `kubelet-xyzf`.
   */
  reportingInstance?: string;
  /**
   * Data about the Event series this event represents or nil if it's a singleton Event.
   */
  series?: IoK8SApiCoreV192;
  /**
   * The component reporting this event. Should be a short machine understandable string.
   */
  source?: IoK8SApiCoreV193;
  /**
   * Type of this event (Normal, Warning), new types could be added in the future
   */
  type?: string;
  [k: string]: unknown;
}
/**
 * EventSeries contain information on series of events, i.e. thing that was/is happening continuously for some time.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EventSeries".
 */
export interface IoK8SApiCoreV192 {
  /**
   * Number of occurrences in this series up to the last heartbeat time
   */
  count?: number;
  /**
   * Time of the last occurrence observed
   */
  lastObservedTime?: IoK8SApimachineryPkgApisMetaV1MicroTime;
  [k: string]: unknown;
}
/**
 * EventSource contains information for an event.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EventSource".
 */
export interface IoK8SApiCoreV193 {
  /**
   * Component from which the event is generated.
   */
  component?: string;
  /**
   * Node name on which the event is generated.
   */
  host?: string;
  [k: string]: unknown;
}
/**
 * EventList is a list of events.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.EventList".
 */
export interface IoK8SApiCoreV1EventList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of events
   */
  items: IoK8SApiCoreV191[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * Represents a Fibre Channel volume. Fibre Channel volumes can only be mounted as read/write once. Fibre Channel volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.FCVolumeSource".
 */
export interface IoK8SApiCoreV194 {
  /**
   * fsType is the filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified.
   */
  fsType?: string;
  /**
   * lun is Optional: FC target lun number
   */
  lun?: number;
  /**
   * readOnly is Optional: Defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * targetWWNs is Optional: FC target worldwide names (WWNs)
   */
  targetWWNs?: string[];
  /**
   * wwids Optional: FC volume world wide identifiers (wwids) Either wwids or combination of targetWWNs and lun must be set, but not both simultaneously.
   */
  wwids?: string[];
  [k: string]: unknown;
}
/**
 * FlexPersistentVolumeSource represents a generic persistent volume resource that is provisioned/attached using an exec based plugin.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.FlexPersistentVolumeSource".
 */
export interface IoK8SApiCoreV195 {
  /**
   * driver is the name of the driver to use for this volume.
   */
  driver: string;
  /**
   * fsType is the Filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". The default filesystem depends on FlexVolume script.
   */
  fsType?: string;
  /**
   * options is Optional: this field holds extra command options if any.
   */
  options?: {
    [k: string]: string;
  };
  /**
   * readOnly is Optional: defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * secretRef is Optional: SecretRef is reference to the secret object containing sensitive information to pass to the plugin scripts. This may be empty if no secret object is specified. If the secret object contains more than one secret, all secrets are passed to the plugin scripts.
   */
  secretRef?: IoK8SApiCoreV118;
  [k: string]: unknown;
}
/**
 * FlexVolume represents a generic volume resource that is provisioned/attached using an exec based plugin.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.FlexVolumeSource".
 */
export interface IoK8SApiCoreV196 {
  /**
   * driver is the name of the driver to use for this volume.
   */
  driver: string;
  /**
   * fsType is the filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". The default filesystem depends on FlexVolume script.
   */
  fsType?: string;
  /**
   * options is Optional: this field holds extra command options if any.
   */
  options?: {
    [k: string]: string;
  };
  /**
   * readOnly is Optional: defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * secretRef is Optional: secretRef is reference to the secret object containing sensitive information to pass to the plugin scripts. This may be empty if no secret object is specified. If the secret object contains more than one secret, all secrets are passed to the plugin scripts.
   */
  secretRef?: IoK8SApiCoreV120;
  [k: string]: unknown;
}
/**
 * Represents a Flocker volume mounted by the Flocker agent. One and only one of datasetName and datasetUUID should be set. Flocker volumes do not support ownership management or SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.FlockerVolumeSource".
 */
export interface IoK8SApiCoreV197 {
  /**
   * datasetName is Name of the dataset stored as metadata -> name on the dataset for Flocker should be considered as deprecated
   */
  datasetName?: string;
  /**
   * datasetUUID is the UUID of the dataset. This is unique identifier of a Flocker dataset
   */
  datasetUUID?: string;
  [k: string]: unknown;
}
/**
 * Represents a Persistent Disk resource in Google Compute Engine.
 *
 * A GCE PD must exist before mounting to a container. The disk must also be in the same GCE project and zone as the kubelet. A GCE PD can only be mounted as read/write once or read-only many times. GCE PDs support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.GCEPersistentDiskVolumeSource".
 */
export interface IoK8SApiCoreV198 {
  /**
   * fsType is filesystem type of the volume that you want to mount. Tip: Ensure that the filesystem type is supported by the host operating system. Examples: "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified. More info: https://kubernetes.io/docs/concepts/storage/volumes#gcepersistentdisk
   */
  fsType?: string;
  /**
   * partition is the partition in the volume that you want to mount. If omitted, the default is to mount by volume name. Examples: For volume /dev/sda1, you specify the partition as "1". Similarly, the volume partition for /dev/sda is "0" (or you can leave the property empty). More info: https://kubernetes.io/docs/concepts/storage/volumes#gcepersistentdisk
   */
  partition?: number;
  /**
   * pdName is unique name of the PD resource in GCE. Used to identify the disk in GCE. More info: https://kubernetes.io/docs/concepts/storage/volumes#gcepersistentdisk
   */
  pdName: string;
  /**
   * readOnly here will force the ReadOnly setting in VolumeMounts. Defaults to false. More info: https://kubernetes.io/docs/concepts/storage/volumes#gcepersistentdisk
   */
  readOnly?: boolean;
  [k: string]: unknown;
}
/**
 * Represents a volume that is populated with the contents of a git repository. Git repo volumes do not support ownership management. Git repo volumes support SELinux relabeling.
 *
 * DEPRECATED: GitRepo is deprecated. To provision a container with a git repo, mount an EmptyDir into an InitContainer that clones the repo using git, then mount the EmptyDir into the Pod's container.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.GitRepoVolumeSource".
 */
export interface IoK8SApiCoreV199 {
  /**
   * directory is the target directory name. Must not contain or start with '..'.  If '.' is supplied, the volume directory will be the git repository.  Otherwise, if specified, the volume will contain the git repository in the subdirectory with the given name.
   */
  directory?: string;
  /**
   * repository is the URL
   */
  repository: string;
  /**
   * revision is the commit hash for the specified revision.
   */
  revision?: string;
  [k: string]: unknown;
}
/**
 * Represents a Glusterfs mount that lasts the lifetime of a pod. Glusterfs volumes do not support ownership management or SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.GlusterfsPersistentVolumeSource".
 */
export interface IoK8SApiCoreV1100 {
  /**
   * endpoints is the endpoint name that details Glusterfs topology. More info: https://examples.k8s.io/volumes/glusterfs/README.md#create-a-pod
   */
  endpoints: string;
  /**
   * endpointsNamespace is the namespace that contains Glusterfs endpoint. If this field is empty, the EndpointNamespace defaults to the same namespace as the bound PVC. More info: https://examples.k8s.io/volumes/glusterfs/README.md#create-a-pod
   */
  endpointsNamespace?: string;
  /**
   * path is the Glusterfs volume path. More info: https://examples.k8s.io/volumes/glusterfs/README.md#create-a-pod
   */
  path: string;
  /**
   * readOnly here will force the Glusterfs volume to be mounted with read-only permissions. Defaults to false. More info: https://examples.k8s.io/volumes/glusterfs/README.md#create-a-pod
   */
  readOnly?: boolean;
  [k: string]: unknown;
}
/**
 * Represents a Glusterfs mount that lasts the lifetime of a pod. Glusterfs volumes do not support ownership management or SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.GlusterfsVolumeSource".
 */
export interface IoK8SApiCoreV1101 {
  /**
   * endpoints is the endpoint name that details Glusterfs topology. More info: https://examples.k8s.io/volumes/glusterfs/README.md#create-a-pod
   */
  endpoints: string;
  /**
   * path is the Glusterfs volume path. More info: https://examples.k8s.io/volumes/glusterfs/README.md#create-a-pod
   */
  path: string;
  /**
   * readOnly here will force the Glusterfs volume to be mounted with read-only permissions. Defaults to false. More info: https://examples.k8s.io/volumes/glusterfs/README.md#create-a-pod
   */
  readOnly?: boolean;
  [k: string]: unknown;
}
/**
 * HostAlias holds the mapping between IP and hostnames that will be injected as an entry in the pod's hosts file.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.HostAlias".
 */
export interface IoK8SApiCoreV1102 {
  /**
   * Hostnames for the above IP address.
   */
  hostnames?: string[];
  /**
   * IP address of the host file entry.
   */
  ip: string;
  [k: string]: unknown;
}
/**
 * HostIP represents a single IP address allocated to the host.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.HostIP".
 */
export interface IoK8SApiCoreV1103 {
  /**
   * IP is the IP address assigned to the host
   */
  ip: string;
  [k: string]: unknown;
}
/**
 * Represents a host path mapped into a pod. Host path volumes do not support ownership management or SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.HostPathVolumeSource".
 */
export interface IoK8SApiCoreV1104 {
  /**
   * path of the directory on the host. If the path is a symlink, it will follow the link to the real path. More info: https://kubernetes.io/docs/concepts/storage/volumes#hostpath
   */
  path: string;
  /**
   * type for HostPath Volume Defaults to "" More info: https://kubernetes.io/docs/concepts/storage/volumes#hostpath
   *
   * Possible enum values:
   *  - `""` For backwards compatible, leave it empty if unset
   *  - `"BlockDevice"` A block device must exist at the given path
   *  - `"CharDevice"` A character device must exist at the given path
   *  - `"Directory"` A directory must exist at the given path
   *  - `"DirectoryOrCreate"` If nothing exists at the given path, an empty directory will be created there as needed with file mode 0755, having the same group and ownership with Kubelet.
   *  - `"File"` A file must exist at the given path
   *  - `"FileOrCreate"` If nothing exists at the given path, an empty file will be created there as needed with file mode 0644, having the same group and ownership with Kubelet.
   *  - `"Socket"` A UNIX socket must exist at the given path
   */
  type?: "" | "BlockDevice" | "CharDevice" | "Directory" | "DirectoryOrCreate" | "File" | "FileOrCreate" | "Socket";
  [k: string]: unknown;
}
/**
 * ISCSIPersistentVolumeSource represents an ISCSI disk. ISCSI volumes can only be mounted as read/write once. ISCSI volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ISCSIPersistentVolumeSource".
 */
export interface IoK8SApiCoreV1105 {
  /**
   * chapAuthDiscovery defines whether support iSCSI Discovery CHAP authentication
   */
  chapAuthDiscovery?: boolean;
  /**
   * chapAuthSession defines whether support iSCSI Session CHAP authentication
   */
  chapAuthSession?: boolean;
  /**
   * fsType is the filesystem type of the volume that you want to mount. Tip: Ensure that the filesystem type is supported by the host operating system. Examples: "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified. More info: https://kubernetes.io/docs/concepts/storage/volumes#iscsi
   */
  fsType?: string;
  /**
   * initiatorName is the custom iSCSI Initiator Name. If initiatorName is specified with iscsiInterface simultaneously, new iSCSI interface <target portal>:<volume name> will be created for the connection.
   */
  initiatorName?: string;
  /**
   * iqn is Target iSCSI Qualified Name.
   */
  iqn: string;
  /**
   * iscsiInterface is the interface Name that uses an iSCSI transport. Defaults to 'default' (tcp).
   */
  iscsiInterface?: string;
  /**
   * lun is iSCSI Target Lun number.
   */
  lun: number;
  /**
   * portals is the iSCSI Target Portal List. The Portal is either an IP or ip_addr:port if the port is other than default (typically TCP ports 860 and 3260).
   */
  portals?: string[];
  /**
   * readOnly here will force the ReadOnly setting in VolumeMounts. Defaults to false.
   */
  readOnly?: boolean;
  /**
   * secretRef is the CHAP Secret for iSCSI target and initiator authentication
   */
  secretRef?: IoK8SApiCoreV118;
  /**
   * targetPortal is iSCSI Target Portal. The Portal is either an IP or ip_addr:port if the port is other than default (typically TCP ports 860 and 3260).
   */
  targetPortal: string;
  [k: string]: unknown;
}
/**
 * Represents an ISCSI disk. ISCSI volumes can only be mounted as read/write once. ISCSI volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ISCSIVolumeSource".
 */
export interface IoK8SApiCoreV1106 {
  /**
   * chapAuthDiscovery defines whether support iSCSI Discovery CHAP authentication
   */
  chapAuthDiscovery?: boolean;
  /**
   * chapAuthSession defines whether support iSCSI Session CHAP authentication
   */
  chapAuthSession?: boolean;
  /**
   * fsType is the filesystem type of the volume that you want to mount. Tip: Ensure that the filesystem type is supported by the host operating system. Examples: "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified. More info: https://kubernetes.io/docs/concepts/storage/volumes#iscsi
   */
  fsType?: string;
  /**
   * initiatorName is the custom iSCSI Initiator Name. If initiatorName is specified with iscsiInterface simultaneously, new iSCSI interface <target portal>:<volume name> will be created for the connection.
   */
  initiatorName?: string;
  /**
   * iqn is the target iSCSI Qualified Name.
   */
  iqn: string;
  /**
   * iscsiInterface is the interface Name that uses an iSCSI transport. Defaults to 'default' (tcp).
   */
  iscsiInterface?: string;
  /**
   * lun represents iSCSI Target Lun number.
   */
  lun: number;
  /**
   * portals is the iSCSI Target Portal List. The portal is either an IP or ip_addr:port if the port is other than default (typically TCP ports 860 and 3260).
   */
  portals?: string[];
  /**
   * readOnly here will force the ReadOnly setting in VolumeMounts. Defaults to false.
   */
  readOnly?: boolean;
  /**
   * secretRef is the CHAP Secret for iSCSI target and initiator authentication
   */
  secretRef?: IoK8SApiCoreV120;
  /**
   * targetPortal is iSCSI Target Portal. The Portal is either an IP or ip_addr:port if the port is other than default (typically TCP ports 860 and 3260).
   */
  targetPortal: string;
  [k: string]: unknown;
}
/**
 * ImageVolumeSource represents a image volume resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ImageVolumeSource".
 */
export interface IoK8SApiCoreV1107 {
  /**
   * Policy for pulling OCI objects. Possible values are: Always: the kubelet always attempts to pull the reference. Container creation will fail If the pull fails. Never: the kubelet never pulls the reference and only uses a local image or artifact. Container creation will fail if the reference isn't present. IfNotPresent: the kubelet pulls if the reference isn't already present on disk. Container creation will fail if the reference isn't present and the pull fails. Defaults to Always if :latest tag is specified, or IfNotPresent otherwise.
   *
   * Possible enum values:
   *  - `"Always"` means that kubelet always attempts to pull the latest image. Container will fail If the pull fails.
   *  - `"IfNotPresent"` means that kubelet pulls if the image isn't present on disk. Container will fail if the image isn't present and the pull fails.
   *  - `"Never"` means that kubelet never pulls an image, but only uses a local image. Container will fail if the image isn't present
   */
  pullPolicy?: "Always" | "IfNotPresent" | "Never";
  /**
   * Required: Image or artifact reference to be used. Behaves in the same way as pod.spec.containers[*].image. Pull secrets will be assembled in the same way as for the container image by looking up node credentials, SA image pull secrets, and pod spec image pull secrets. More info: https://kubernetes.io/docs/concepts/containers/images This field is optional to allow higher level config management to default or override container images in workload controllers like Deployments and StatefulSets.
   */
  reference?: string;
  [k: string]: unknown;
}
/**
 * LimitRange sets resource usage limits for each kind of resource in a Namespace.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LimitRange".
 */
export interface IoK8SApiCoreV1108 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec defines the limits enforced. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiCoreV1109;
  [k: string]: unknown;
}
/**
 * LimitRangeSpec defines a min/max usage limit for resources that match on kind.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LimitRangeSpec".
 */
export interface IoK8SApiCoreV1109 {
  /**
   * Limits is the list of LimitRangeItem objects that are enforced.
   */
  limits: IoK8SApiCoreV1110[];
  [k: string]: unknown;
}
/**
 * LimitRangeItem defines a min/max usage limit for any resource that matches on kind.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LimitRangeItem".
 */
export interface IoK8SApiCoreV1110 {
  /**
   * Default resource requirement limit value by resource name if resource limit is omitted.
   */
  default?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * DefaultRequest is the default resource requirement request value by resource name if resource request is omitted.
   */
  defaultRequest?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * Max usage constraints on this kind by resource name.
   */
  max?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * MaxLimitRequestRatio if specified, the named resource must have a request and limit that are both non-zero where limit divided by request is less than or equal to the enumerated value; this represents the max burst for the named resource.
   */
  maxLimitRequestRatio?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * Min usage constraints on this kind by resource name.
   */
  min?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * Type of resource that this limit applies to.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * LimitRangeList is a list of LimitRange items.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LimitRangeList".
 */
export interface IoK8SApiCoreV1LimitRangeList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Items is a list of LimitRange objects. More info: https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
   */
  items: IoK8SApiCoreV1108[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * LoadBalancerIngress represents the status of a load-balancer ingress point: traffic intended for the service should be sent to an ingress point.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LoadBalancerIngress".
 */
export interface IoK8SApiCoreV1111 {
  /**
   * Hostname is set for load-balancer ingress points that are DNS based (typically AWS load-balancers)
   */
  hostname?: string;
  /**
   * IP is set for load-balancer ingress points that are IP based (typically GCE or OpenStack load-balancers)
   */
  ip?: string;
  /**
   * IPMode specifies how the load-balancer IP behaves, and may only be specified when the ip field is specified. Setting this to "VIP" indicates that traffic is delivered to the node with the destination set to the load-balancer's IP and port. Setting this to "Proxy" indicates that traffic is delivered to the node or pod with the destination set to the node's IP and node port or the pod's IP and port. Service implementations may use this information to adjust traffic routing.
   */
  ipMode?: string;
  /**
   * Ports is a list of records of service ports If used, every port defined in the service should have an entry in it
   */
  ports?: IoK8SApiCoreV1112[];
  [k: string]: unknown;
}
/**
 * PortStatus represents the error condition of a service port
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PortStatus".
 */
export interface IoK8SApiCoreV1112 {
  /**
   * Error is to record the problem with the service port The format of the error shall comply with the following rules: - built-in error values shall be specified in this file and those shall use
   *   CamelCase names
   * - cloud provider specific error values must have names that comply with the
   *   format foo.example.com/CamelCase.
   */
  error?: string;
  /**
   * Port is the port number of the service port of which status is recorded here
   */
  port: number;
  /**
   * Protocol is the protocol of the service port of which status is recorded here The supported values are: "TCP", "UDP", "SCTP"
   *
   * Possible enum values:
   *  - `"SCTP"` is the SCTP protocol.
   *  - `"TCP"` is the TCP protocol.
   *  - `"UDP"` is the UDP protocol.
   */
  protocol: "SCTP" | "TCP" | "UDP";
  [k: string]: unknown;
}
/**
 * LoadBalancerStatus represents the status of a load-balancer.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LoadBalancerStatus".
 */
export interface IoK8SApiCoreV1113 {
  /**
   * Ingress is a list containing ingress points for the load-balancer. Traffic intended for the service should be sent to these ingress points.
   */
  ingress?: IoK8SApiCoreV1111[];
  [k: string]: unknown;
}
/**
 * Local represents directly-attached storage with node affinity
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.LocalVolumeSource".
 */
export interface IoK8SApiCoreV1114 {
  /**
   * fsType is the filesystem type to mount. It applies only when the Path is a block device. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". The default value is to auto-select a filesystem if unspecified.
   */
  fsType?: string;
  /**
   * path of the full path to the volume on the node. It can be either a directory or block device (disk, partition, ...).
   */
  path: string;
  [k: string]: unknown;
}
/**
 * ModifyVolumeStatus represents the status object of ControllerModifyVolume operation
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ModifyVolumeStatus".
 */
export interface IoK8SApiCoreV1115 {
  /**
   * status is the status of the ControllerModifyVolume operation. It can be in any of following states:
   *  - Pending
   *    Pending indicates that the PersistentVolumeClaim cannot be modified due to unmet requirements, such as
   *    the specified VolumeAttributesClass not existing.
   *  - InProgress
   *    InProgress indicates that the volume is being modified.
   *  - Infeasible
   *   Infeasible indicates that the request has been rejected as invalid by the CSI driver. To
   * 	  resolve the error, a valid VolumeAttributesClass needs to be specified.
   * Note: New statuses can be added in the future. Consumers should check for unknown statuses and fail appropriately.
   *
   * Possible enum values:
   *  - `"InProgress"` InProgress indicates that the volume is being modified
   *  - `"Infeasible"` Infeasible indicates that the request has been rejected as invalid by the CSI driver. To resolve the error, a valid VolumeAttributesClass needs to be specified
   *  - `"Pending"` Pending indicates that the PersistentVolumeClaim cannot be modified due to unmet requirements, such as the specified VolumeAttributesClass not existing
   */
  status: "InProgress" | "Infeasible" | "Pending";
  /**
   * targetVolumeAttributesClassName is the name of the VolumeAttributesClass the PVC currently being reconciled
   */
  targetVolumeAttributesClassName?: string;
  [k: string]: unknown;
}
/**
 * Represents an NFS mount that lasts the lifetime of a pod. NFS volumes do not support ownership management or SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NFSVolumeSource".
 */
export interface IoK8SApiCoreV1116 {
  /**
   * path that is exported by the NFS server. More info: https://kubernetes.io/docs/concepts/storage/volumes#nfs
   */
  path: string;
  /**
   * readOnly here will force the NFS export to be mounted with read-only permissions. Defaults to false. More info: https://kubernetes.io/docs/concepts/storage/volumes#nfs
   */
  readOnly?: boolean;
  /**
   * server is the hostname or IP address of the NFS server. More info: https://kubernetes.io/docs/concepts/storage/volumes#nfs
   */
  server: string;
  [k: string]: unknown;
}
/**
 * Namespace provides a scope for Names. Use of multiple namespaces is optional.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Namespace".
 */
export interface IoK8SApiCoreV1117 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec defines the behavior of the Namespace. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiCoreV1118;
  /**
   * Status describes the current status of a Namespace. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiCoreV1119;
  [k: string]: unknown;
}
/**
 * NamespaceSpec describes the attributes on a Namespace.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NamespaceSpec".
 */
export interface IoK8SApiCoreV1118 {
  /**
   * Finalizers is an opaque list of values that must be empty to permanently remove object from storage. More info: https://kubernetes.io/docs/tasks/administer-cluster/namespaces/
   */
  finalizers?: string[];
  [k: string]: unknown;
}
/**
 * NamespaceStatus is information about the current status of a Namespace.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NamespaceStatus".
 */
export interface IoK8SApiCoreV1119 {
  /**
   * Represents the latest available observations of a namespace's current state.
   */
  conditions?: IoK8SApiCoreV1120[];
  /**
   * Phase is the current lifecycle phase of the namespace. More info: https://kubernetes.io/docs/tasks/administer-cluster/namespaces/
   *
   * Possible enum values:
   *  - `"Active"` means the namespace is available for use in the system
   *  - `"Terminating"` means the namespace is undergoing graceful termination
   */
  phase?: "Active" | "Terminating";
  [k: string]: unknown;
}
/**
 * NamespaceCondition contains details about state of namespace.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NamespaceCondition".
 */
export interface IoK8SApiCoreV1120 {
  /**
   * Last time the condition transitioned from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Human-readable message indicating details about last transition.
   */
  message?: string;
  /**
   * Unique, one-word, CamelCase reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * Type of namespace controller condition.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * NamespaceList is a list of Namespaces.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NamespaceList".
 */
export interface IoK8SApiCoreV1NamespaceList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Items is the list of Namespace objects in the list. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/
   */
  items: IoK8SApiCoreV1117[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * Node is a worker node in Kubernetes. Each node will have a unique identifier in the cache (i.e. in etcd).
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Node".
 */
export interface IoK8SApiCoreV1121 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec defines the behavior of a node. https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiCoreV1122;
  /**
   * Most recently observed status of the node. Populated by the system. Read-only. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiCoreV1125;
  [k: string]: unknown;
}
/**
 * NodeSpec describes the attributes that a node is created with.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeSpec".
 */
export interface IoK8SApiCoreV1122 {
  /**
   * Deprecated: Previously used to specify the source of the node's configuration for the DynamicKubeletConfig feature. This feature is removed.
   */
  configSource?: IoK8SApiCoreV1123;
  /**
   * Deprecated. Not all kubelets will set this field. Remove field after 1.13. see: https://issues.k8s.io/61966
   */
  externalID?: string;
  /**
   * PodCIDR represents the pod IP range assigned to the node.
   */
  podCIDR?: string;
  /**
   * podCIDRs represents the IP ranges assigned to the node for usage by Pods on that node. If this field is specified, the 0th entry must match the podCIDR field. It may contain at most 1 value for each of IPv4 and IPv6.
   */
  podCIDRs?: string[];
  /**
   * ID of the node assigned by the cloud provider in the format: <ProviderName>://<ProviderSpecificNodeID>
   */
  providerID?: string;
  /**
   * If specified, the node's taints.
   */
  taints?: IoK8SApiCoreV1124[];
  /**
   * Unschedulable controls node schedulability of new pods. By default, node is schedulable. More info: https://kubernetes.io/docs/concepts/nodes/node/#manual-node-administration
   */
  unschedulable?: boolean;
  [k: string]: unknown;
}
/**
 * NodeConfigSource specifies a source of node configuration. Exactly one subfield (excluding metadata) must be non-nil. This API is deprecated since 1.22
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeConfigSource".
 */
export interface IoK8SApiCoreV1123 {
  /**
   * ConfigMap is a reference to a Node's ConfigMap
   */
  configMap?: IoK8SApiCoreV133;
  [k: string]: unknown;
}
/**
 * The node this Taint is attached to has the "effect" on any pod that does not tolerate the Taint.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Taint".
 */
export interface IoK8SApiCoreV1124 {
  /**
   * Required. The effect of the taint on pods that do not tolerate the taint. Valid effects are NoSchedule, PreferNoSchedule and NoExecute.
   *
   * Possible enum values:
   *  - `"NoExecute"` Evict any already-running pods that do not tolerate the taint. Currently enforced by NodeController.
   *  - `"NoSchedule"` Do not allow new pods to schedule onto the node unless they tolerate the taint, but allow all pods submitted to Kubelet without going through the scheduler to start, and allow all already-running pods to continue running. Enforced by the scheduler.
   *  - `"PreferNoSchedule"` Like TaintEffectNoSchedule, but the scheduler tries not to schedule new pods onto the node, rather than prohibiting new pods from scheduling onto the node entirely. Enforced by the scheduler.
   */
  effect: "NoExecute" | "NoSchedule" | "PreferNoSchedule";
  /**
   * Required. The taint key to be applied to a node.
   */
  key: string;
  /**
   * TimeAdded represents the time at which the taint was added. It is only written for NoExecute taints.
   */
  timeAdded?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * The taint value corresponding to the taint key.
   */
  value?: string;
  [k: string]: unknown;
}
/**
 * NodeStatus is information about the current status of a node.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeStatus".
 */
export interface IoK8SApiCoreV1125 {
  /**
   * List of addresses reachable to the node. Queried from cloud provider, if available. More info: https://kubernetes.io/docs/reference/node/node-status/#addresses Note: This field is declared as mergeable, but the merge key is not sufficiently unique, which can cause data corruption when it is merged. Callers should instead use a full-replacement patch. See https://pr.k8s.io/79391 for an example. Consumers should assume that addresses can change during the lifetime of a Node. However, there are some exceptions where this may not be possible, such as Pods that inherit a Node's address in its own status or consumers of the downward API (status.hostIP).
   */
  addresses?: IoK8SApiCoreV1126[];
  /**
   * Allocatable represents the resources of a node that are available for scheduling. Defaults to Capacity.
   */
  allocatable?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * Capacity represents the total resources of a node. More info: https://kubernetes.io/docs/reference/node/node-status/#capacity
   */
  capacity?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * Conditions is an array of current observed node conditions. More info: https://kubernetes.io/docs/reference/node/node-status/#condition
   */
  conditions?: IoK8SApiCoreV1127[];
  /**
   * Status of the config assigned to the node via the dynamic Kubelet config feature.
   */
  config?: IoK8SApiCoreV1128;
  /**
   * Endpoints of daemons running on the Node.
   */
  daemonEndpoints?: IoK8SApiCoreV1129;
  /**
   * Features describes the set of features implemented by the CRI implementation.
   */
  features?: IoK8SApiCoreV1130;
  /**
   * List of container images on this node
   */
  images?: IoK8SApiCoreV164[];
  /**
   * Set of ids/uuids to uniquely identify the node. More info: https://kubernetes.io/docs/reference/node/node-status/#info
   */
  nodeInfo?: IoK8SApiCoreV1131;
  /**
   * NodePhase is the recently observed lifecycle phase of the node. More info: https://kubernetes.io/docs/concepts/nodes/node/#phase The field is never populated, and now is deprecated.
   *
   * Possible enum values:
   *  - `"Pending"` means the node has been created/added by the system, but not configured.
   *  - `"Running"` means the node has been configured and has Kubernetes components running.
   *  - `"Terminated"` means the node has been removed from the cluster.
   */
  phase?: "Pending" | "Running" | "Terminated";
  /**
   * The available runtime handlers.
   */
  runtimeHandlers?: IoK8SApiCoreV1132[];
  /**
   * List of volumes that are attached to the node.
   */
  volumesAttached?: IoK8SApiCoreV112[];
  /**
   * List of attachable volumes in use (mounted) by the node.
   */
  volumesInUse?: string[];
  [k: string]: unknown;
}
/**
 * NodeAddress contains information for the node's address.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeAddress".
 */
export interface IoK8SApiCoreV1126 {
  /**
   * The node address.
   */
  address: string;
  /**
   * Node address type, one of Hostname, ExternalIP or InternalIP.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * NodeCondition contains condition information for a node.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeCondition".
 */
export interface IoK8SApiCoreV1127 {
  /**
   * Last time we got an update on a given condition.
   */
  lastHeartbeatTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Last time the condition transit from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Human readable message indicating details about last transition.
   */
  message?: string;
  /**
   * (brief) reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * Type of node condition.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * NodeConfigStatus describes the status of the config assigned by Node.Spec.ConfigSource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeConfigStatus".
 */
export interface IoK8SApiCoreV1128 {
  /**
   * Active reports the checkpointed config the node is actively using. Active will represent either the current version of the Assigned config, or the current LastKnownGood config, depending on whether attempting to use the Assigned config results in an error.
   */
  active?: IoK8SApiCoreV1123;
  /**
   * Assigned reports the checkpointed config the node will try to use. When Node.Spec.ConfigSource is updated, the node checkpoints the associated config payload to local disk, along with a record indicating intended config. The node refers to this record to choose its config checkpoint, and reports this record in Assigned. Assigned only updates in the status after the record has been checkpointed to disk. When the Kubelet is restarted, it tries to make the Assigned config the Active config by loading and validating the checkpointed payload identified by Assigned.
   */
  assigned?: IoK8SApiCoreV1123;
  /**
   * Error describes any problems reconciling the Spec.ConfigSource to the Active config. Errors may occur, for example, attempting to checkpoint Spec.ConfigSource to the local Assigned record, attempting to checkpoint the payload associated with Spec.ConfigSource, attempting to load or validate the Assigned config, etc. Errors may occur at different points while syncing config. Earlier errors (e.g. download or checkpointing errors) will not result in a rollback to LastKnownGood, and may resolve across Kubelet retries. Later errors (e.g. loading or validating a checkpointed config) will result in a rollback to LastKnownGood. In the latter case, it is usually possible to resolve the error by fixing the config assigned in Spec.ConfigSource. You can find additional information for debugging by searching the error message in the Kubelet log. Error is a human-readable description of the error state; machines can check whether or not Error is empty, but should not rely on the stability of the Error text across Kubelet versions.
   */
  error?: string;
  /**
   * LastKnownGood reports the checkpointed config the node will fall back to when it encounters an error attempting to use the Assigned config. The Assigned config becomes the LastKnownGood config when the node determines that the Assigned config is stable and correct. This is currently implemented as a 10-minute soak period starting when the local record of Assigned config is updated. If the Assigned config is Active at the end of this period, it becomes the LastKnownGood. Note that if Spec.ConfigSource is reset to nil (use local defaults), the LastKnownGood is also immediately reset to nil, because the local default config is always assumed good. You should not make assumptions about the node's method of determining config stability and correctness, as this may change or become configurable in the future.
   */
  lastKnownGood?: IoK8SApiCoreV1123;
  [k: string]: unknown;
}
/**
 * NodeDaemonEndpoints lists ports opened by daemons running on the Node.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeDaemonEndpoints".
 */
export interface IoK8SApiCoreV1129 {
  /**
   * Endpoint on which Kubelet is listening.
   */
  kubeletEndpoint?: IoK8SApiCoreV175;
  [k: string]: unknown;
}
/**
 * NodeFeatures describes the set of features implemented by the CRI implementation. The features contained in the NodeFeatures should depend only on the cri implementation independent of runtime handlers.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeFeatures".
 */
export interface IoK8SApiCoreV1130 {
  /**
   * SupplementalGroupsPolicy is set to true if the runtime supports SupplementalGroupsPolicy and ContainerUser.
   */
  supplementalGroupsPolicy?: boolean;
  [k: string]: unknown;
}
/**
 * NodeSystemInfo is a set of ids/uuids to uniquely identify the node.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeSystemInfo".
 */
export interface IoK8SApiCoreV1131 {
  /**
   * The Architecture reported by the node
   */
  architecture: string;
  /**
   * Boot ID reported by the node.
   */
  bootID: string;
  /**
   * ContainerRuntime Version reported by the node through runtime remote API (e.g. containerd://1.4.2).
   */
  containerRuntimeVersion: string;
  /**
   * Kernel Version reported by the node from 'uname -r' (e.g. 3.16.0-0.bpo.4-amd64).
   */
  kernelVersion: string;
  /**
   * Deprecated: KubeProxy Version reported by the node.
   */
  kubeProxyVersion: string;
  /**
   * Kubelet Version reported by the node.
   */
  kubeletVersion: string;
  /**
   * MachineID reported by the node. For unique machine identification in the cluster this field is preferred. Learn more from man(5) machine-id: http://man7.org/linux/man-pages/man5/machine-id.5.html
   */
  machineID: string;
  /**
   * The Operating System reported by the node
   */
  operatingSystem: string;
  /**
   * OS Image reported by the node from /etc/os-release (e.g. Debian GNU/Linux 7 (wheezy)).
   */
  osImage: string;
  /**
   * SystemUUID reported by the node. For unique machine identification MachineID is preferred. This field is specific to Red Hat hosts https://access.redhat.com/documentation/en-us/red_hat_subscription_management/1/html/rhsm/uuid
   */
  systemUUID: string;
  [k: string]: unknown;
}
/**
 * NodeRuntimeHandler is a set of runtime handler information.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeRuntimeHandler".
 */
export interface IoK8SApiCoreV1132 {
  /**
   * Supported features.
   */
  features?: IoK8SApiCoreV1133;
  /**
   * Runtime handler name. Empty for the default runtime handler.
   */
  name?: string;
  [k: string]: unknown;
}
/**
 * NodeRuntimeHandlerFeatures is a set of features implemented by the runtime handler.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeRuntimeHandlerFeatures".
 */
export interface IoK8SApiCoreV1133 {
  /**
   * RecursiveReadOnlyMounts is set to true if the runtime handler supports RecursiveReadOnlyMounts.
   */
  recursiveReadOnlyMounts?: boolean;
  /**
   * UserNamespaces is set to true if the runtime handler supports UserNamespaces, including for volumes.
   */
  userNamespaces?: boolean;
  [k: string]: unknown;
}
/**
 * NodeList is the whole list of all Nodes which have been registered with master.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.NodeList".
 */
export interface IoK8SApiCoreV1NodeList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of nodes
   */
  items: IoK8SApiCoreV1121[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * PersistentVolume (PV) is a storage resource provisioned by an administrator. It is analogous to a node. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolume".
 */
export interface IoK8SApiCoreV1134 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * spec defines a specification of a persistent volume owned by the cluster. Provisioned by an administrator. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#persistent-volumes
   */
  spec?: IoK8SApiCoreV1135;
  /**
   * status represents the current information/status for the persistent volume. Populated by the system. Read-only. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#persistent-volumes
   */
  status?: IoK8SApiCoreV1144;
  [k: string]: unknown;
}
/**
 * PersistentVolumeSpec is the specification of a persistent volume.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeSpec".
 */
export interface IoK8SApiCoreV1135 {
  /**
   * accessModes contains all ways the volume can be mounted. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#access-modes
   */
  accessModes?: ("ReadOnlyMany" | "ReadWriteMany" | "ReadWriteOnce" | "ReadWriteOncePod")[];
  /**
   * awsElasticBlockStore represents an AWS Disk resource that is attached to a kubelet's host machine and then exposed to the pod. Deprecated: AWSElasticBlockStore is deprecated. All operations for the in-tree awsElasticBlockStore type are redirected to the ebs.csi.aws.com CSI driver. More info: https://kubernetes.io/docs/concepts/storage/volumes#awselasticblockstore
   */
  awsElasticBlockStore?: IoK8SApiCoreV1;
  /**
   * azureDisk represents an Azure Data Disk mount on the host and bind mount to the pod. Deprecated: AzureDisk is deprecated. All operations for the in-tree azureDisk type are redirected to the disk.csi.azure.com CSI driver.
   */
  azureDisk?: IoK8SApiCoreV113;
  /**
   * azureFile represents an Azure File Service mount on the host and bind mount to the pod. Deprecated: AzureFile is deprecated. All operations for the in-tree azureFile type are redirected to the file.csi.azure.com CSI driver.
   */
  azureFile?: IoK8SApiCoreV114;
  /**
   * capacity is the description of the persistent volume's resources and capacity. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#capacity
   */
  capacity?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * cephFS represents a Ceph FS mount on the host that shares a pod's lifetime. Deprecated: CephFS is deprecated and the in-tree cephfs type is no longer supported.
   */
  cephfs?: IoK8SApiCoreV122;
  /**
   * cinder represents a cinder volume attached and mounted on kubelets host machine. Deprecated: Cinder is deprecated. All operations for the in-tree cinder type are redirected to the cinder.csi.openstack.org CSI driver. More info: https://examples.k8s.io/mysql-cinder-pd/README.md
   */
  cinder?: IoK8SApiCoreV124;
  /**
   * claimRef is part of a bi-directional binding between PersistentVolume and PersistentVolumeClaim. Expected to be non-nil when bound. claim.VolumeName is the authoritative bind between PV and PVC. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#binding
   */
  claimRef?: IoK8SApiCoreV116;
  /**
   * csi represents storage that is handled by an external CSI driver.
   */
  csi?: IoK8SApiCoreV117;
  /**
   * fc represents a Fibre Channel resource that is attached to a kubelet's host machine and then exposed to the pod.
   */
  fc?: IoK8SApiCoreV194;
  /**
   * flexVolume represents a generic volume resource that is provisioned/attached using an exec based plugin. Deprecated: FlexVolume is deprecated. Consider using a CSIDriver instead.
   */
  flexVolume?: IoK8SApiCoreV195;
  /**
   * flocker represents a Flocker volume attached to a kubelet's host machine and exposed to the pod for its usage. This depends on the Flocker control service being running. Deprecated: Flocker is deprecated and the in-tree flocker type is no longer supported.
   */
  flocker?: IoK8SApiCoreV197;
  /**
   * gcePersistentDisk represents a GCE Disk resource that is attached to a kubelet's host machine and then exposed to the pod. Provisioned by an admin. Deprecated: GCEPersistentDisk is deprecated. All operations for the in-tree gcePersistentDisk type are redirected to the pd.csi.storage.gke.io CSI driver. More info: https://kubernetes.io/docs/concepts/storage/volumes#gcepersistentdisk
   */
  gcePersistentDisk?: IoK8SApiCoreV198;
  /**
   * glusterfs represents a Glusterfs volume that is attached to a host and exposed to the pod. Provisioned by an admin. Deprecated: Glusterfs is deprecated and the in-tree glusterfs type is no longer supported. More info: https://examples.k8s.io/volumes/glusterfs/README.md
   */
  glusterfs?: IoK8SApiCoreV1100;
  /**
   * hostPath represents a directory on the host. Provisioned by a developer or tester. This is useful for single-node development and testing only! On-host storage is not supported in any way and WILL NOT WORK in a multi-node cluster. More info: https://kubernetes.io/docs/concepts/storage/volumes#hostpath
   */
  hostPath?: IoK8SApiCoreV1104;
  /**
   * iscsi represents an ISCSI Disk resource that is attached to a kubelet's host machine and then exposed to the pod. Provisioned by an admin.
   */
  iscsi?: IoK8SApiCoreV1105;
  /**
   * local represents directly-attached storage with node affinity
   */
  local?: IoK8SApiCoreV1114;
  /**
   * mountOptions is the list of mount options, e.g. ["ro", "soft"]. Not validated - mount will simply fail if one is invalid. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes/#mount-options
   */
  mountOptions?: string[];
  /**
   * nfs represents an NFS mount on the host. Provisioned by an admin. More info: https://kubernetes.io/docs/concepts/storage/volumes#nfs
   */
  nfs?: IoK8SApiCoreV1116;
  /**
   * nodeAffinity defines constraints that limit what nodes this volume can be accessed from. This field influences the scheduling of pods that use this volume.
   */
  nodeAffinity?: IoK8SApiCoreV1136;
  /**
   * persistentVolumeReclaimPolicy defines what happens to a persistent volume when released from its claim. Valid options are Retain (default for manually created PersistentVolumes), Delete (default for dynamically provisioned PersistentVolumes), and Recycle (deprecated). Recycle must be supported by the volume plugin underlying this PersistentVolume. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#reclaiming
   *
   * Possible enum values:
   *  - `"Delete"` means the volume will be deleted from Kubernetes on release from its claim. The volume plugin must support Deletion.
   *  - `"Recycle"` means the volume will be recycled back into the pool of unbound persistent volumes on release from its claim. The volume plugin must support Recycling.
   *  - `"Retain"` means the volume will be left in its current phase (Released) for manual reclamation by the administrator. The default policy is Retain.
   */
  persistentVolumeReclaimPolicy?: "Delete" | "Recycle" | "Retain";
  /**
   * photonPersistentDisk represents a PhotonController persistent disk attached and mounted on kubelets host machine. Deprecated: PhotonPersistentDisk is deprecated and the in-tree photonPersistentDisk type is no longer supported.
   */
  photonPersistentDisk?: IoK8SApiCoreV1137;
  /**
   * portworxVolume represents a portworx volume attached and mounted on kubelets host machine. Deprecated: PortworxVolume is deprecated. All operations for the in-tree portworxVolume type are redirected to the pxd.portworx.com CSI driver when the CSIMigrationPortworx feature-gate is on.
   */
  portworxVolume?: IoK8SApiCoreV1138;
  /**
   * quobyte represents a Quobyte mount on the host that shares a pod's lifetime. Deprecated: Quobyte is deprecated and the in-tree quobyte type is no longer supported.
   */
  quobyte?: IoK8SApiCoreV1139;
  /**
   * rbd represents a Rados Block Device mount on the host that shares a pod's lifetime. Deprecated: RBD is deprecated and the in-tree rbd type is no longer supported. More info: https://examples.k8s.io/volumes/rbd/README.md
   */
  rbd?: IoK8SApiCoreV1140;
  /**
   * scaleIO represents a ScaleIO persistent volume attached and mounted on Kubernetes nodes. Deprecated: ScaleIO is deprecated and the in-tree scaleIO type is no longer supported.
   */
  scaleIO?: IoK8SApiCoreV1141;
  /**
   * storageClassName is the name of StorageClass to which this persistent volume belongs. Empty value means that this volume does not belong to any StorageClass.
   */
  storageClassName?: string;
  /**
   * storageOS represents a StorageOS volume that is attached to the kubelet's host machine and mounted into the pod. Deprecated: StorageOS is deprecated and the in-tree storageos type is no longer supported. More info: https://examples.k8s.io/volumes/storageos/README.md
   */
  storageos?: IoK8SApiCoreV1142;
  /**
   * Name of VolumeAttributesClass to which this persistent volume belongs. Empty value is not allowed. When this field is not set, it indicates that this volume does not belong to any VolumeAttributesClass. This field is mutable and can be changed by the CSI driver after a volume has been updated successfully to a new class. For an unbound PersistentVolume, the volumeAttributesClassName will be matched with unbound PersistentVolumeClaims during the binding process. This is a beta field and requires enabling VolumeAttributesClass feature (off by default).
   */
  volumeAttributesClassName?: string;
  /**
   * volumeMode defines if a volume is intended to be used with a formatted filesystem or to remain in raw block state. Value of Filesystem is implied when not included in spec.
   *
   * Possible enum values:
   *  - `"Block"` means the volume will not be formatted with a filesystem and will remain a raw block device.
   *  - `"Filesystem"` means the volume will be or is formatted with a filesystem.
   */
  volumeMode?: "Block" | "Filesystem";
  /**
   * vsphereVolume represents a vSphere volume attached and mounted on kubelets host machine. Deprecated: VsphereVolume is deprecated. All operations for the in-tree vsphereVolume type are redirected to the csi.vsphere.vmware.com CSI driver.
   */
  vsphereVolume?: IoK8SApiCoreV1143;
  [k: string]: unknown;
}
/**
 * VolumeNodeAffinity defines constraints that limit what nodes this volume can be accessed from.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.VolumeNodeAffinity".
 */
export interface IoK8SApiCoreV1136 {
  /**
   * required specifies hard node constraints that must be met.
   */
  required?: IoK8SApiCoreV16;
  [k: string]: unknown;
}
/**
 * Represents a Photon Controller persistent disk resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PhotonPersistentDiskVolumeSource".
 */
export interface IoK8SApiCoreV1137 {
  /**
   * fsType is the filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified.
   */
  fsType?: string;
  /**
   * pdID is the ID that identifies Photon Controller persistent disk
   */
  pdID: string;
  [k: string]: unknown;
}
/**
 * PortworxVolumeSource represents a Portworx volume resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PortworxVolumeSource".
 */
export interface IoK8SApiCoreV1138 {
  /**
   * fSType represents the filesystem type to mount Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs". Implicitly inferred to be "ext4" if unspecified.
   */
  fsType?: string;
  /**
   * readOnly defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * volumeID uniquely identifies a Portworx volume
   */
  volumeID: string;
  [k: string]: unknown;
}
/**
 * Represents a Quobyte mount that lasts the lifetime of a pod. Quobyte volumes do not support ownership management or SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.QuobyteVolumeSource".
 */
export interface IoK8SApiCoreV1139 {
  /**
   * group to map volume access to Default is no group
   */
  group?: string;
  /**
   * readOnly here will force the Quobyte volume to be mounted with read-only permissions. Defaults to false.
   */
  readOnly?: boolean;
  /**
   * registry represents a single or multiple Quobyte Registry services specified as a string as host:port pair (multiple entries are separated with commas) which acts as the central registry for volumes
   */
  registry: string;
  /**
   * tenant owning the given Quobyte volume in the Backend Used with dynamically provisioned Quobyte volumes, value is set by the plugin
   */
  tenant?: string;
  /**
   * user to map volume access to Defaults to serivceaccount user
   */
  user?: string;
  /**
   * volume is a string that references an already created Quobyte volume by name.
   */
  volume: string;
  [k: string]: unknown;
}
/**
 * Represents a Rados Block Device mount that lasts the lifetime of a pod. RBD volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.RBDPersistentVolumeSource".
 */
export interface IoK8SApiCoreV1140 {
  /**
   * fsType is the filesystem type of the volume that you want to mount. Tip: Ensure that the filesystem type is supported by the host operating system. Examples: "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified. More info: https://kubernetes.io/docs/concepts/storage/volumes#rbd
   */
  fsType?: string;
  /**
   * image is the rados image name. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  image: string;
  /**
   * keyring is the path to key ring for RBDUser. Default is /etc/ceph/keyring. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  keyring?: string;
  /**
   * monitors is a collection of Ceph monitors. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  monitors: string[];
  /**
   * pool is the rados pool name. Default is rbd. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  pool?: string;
  /**
   * readOnly here will force the ReadOnly setting in VolumeMounts. Defaults to false. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  readOnly?: boolean;
  /**
   * secretRef is name of the authentication secret for RBDUser. If provided overrides keyring. Default is nil. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  secretRef?: IoK8SApiCoreV118;
  /**
   * user is the rados user name. Default is admin. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  user?: string;
  [k: string]: unknown;
}
/**
 * ScaleIOPersistentVolumeSource represents a persistent ScaleIO volume
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ScaleIOPersistentVolumeSource".
 */
export interface IoK8SApiCoreV1141 {
  /**
   * fsType is the filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". Default is "xfs"
   */
  fsType?: string;
  /**
   * gateway is the host address of the ScaleIO API Gateway.
   */
  gateway: string;
  /**
   * protectionDomain is the name of the ScaleIO Protection Domain for the configured storage.
   */
  protectionDomain?: string;
  /**
   * readOnly defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * secretRef references to the secret for ScaleIO user and other sensitive information. If this is not provided, Login operation will fail.
   */
  secretRef: IoK8SApiCoreV118;
  /**
   * sslEnabled is the flag to enable/disable SSL communication with Gateway, default false
   */
  sslEnabled?: boolean;
  /**
   * storageMode indicates whether the storage for a volume should be ThickProvisioned or ThinProvisioned. Default is ThinProvisioned.
   */
  storageMode?: string;
  /**
   * storagePool is the ScaleIO Storage Pool associated with the protection domain.
   */
  storagePool?: string;
  /**
   * system is the name of the storage system as configured in ScaleIO.
   */
  system: string;
  /**
   * volumeName is the name of a volume already created in the ScaleIO system that is associated with this volume source.
   */
  volumeName?: string;
  [k: string]: unknown;
}
/**
 * Represents a StorageOS persistent volume resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.StorageOSPersistentVolumeSource".
 */
export interface IoK8SApiCoreV1142 {
  /**
   * fsType is the filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified.
   */
  fsType?: string;
  /**
   * readOnly defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * secretRef specifies the secret to use for obtaining the StorageOS API credentials.  If not specified, default values will be attempted.
   */
  secretRef?: IoK8SApiCoreV116;
  /**
   * volumeName is the human-readable name of the StorageOS volume.  Volume names are only unique within a namespace.
   */
  volumeName?: string;
  /**
   * volumeNamespace specifies the scope of the volume within StorageOS.  If no namespace is specified then the Pod's namespace will be used.  This allows the Kubernetes name scoping to be mirrored within StorageOS for tighter integration. Set VolumeName to any name to override the default behaviour. Set to "default" if you are not using namespaces within StorageOS. Namespaces that do not pre-exist within StorageOS will be created.
   */
  volumeNamespace?: string;
  [k: string]: unknown;
}
/**
 * Represents a vSphere volume resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.VsphereVirtualDiskVolumeSource".
 */
export interface IoK8SApiCoreV1143 {
  /**
   * fsType is filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified.
   */
  fsType?: string;
  /**
   * storagePolicyID is the storage Policy Based Management (SPBM) profile ID associated with the StoragePolicyName.
   */
  storagePolicyID?: string;
  /**
   * storagePolicyName is the storage Policy Based Management (SPBM) profile name.
   */
  storagePolicyName?: string;
  /**
   * volumePath is the path that identifies vSphere volume vmdk
   */
  volumePath: string;
  [k: string]: unknown;
}
/**
 * PersistentVolumeStatus is the current status of a persistent volume.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeStatus".
 */
export interface IoK8SApiCoreV1144 {
  /**
   * lastPhaseTransitionTime is the time the phase transitioned from one to another and automatically resets to current time everytime a volume phase transitions.
   */
  lastPhaseTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * message is a human-readable message indicating details about why the volume is in this state.
   */
  message?: string;
  /**
   * phase indicates if a volume is available, bound to a claim, or released by a claim. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#phase
   *
   * Possible enum values:
   *  - `"Available"` used for PersistentVolumes that are not yet bound Available volumes are held by the binder and matched to PersistentVolumeClaims
   *  - `"Bound"` used for PersistentVolumes that are bound
   *  - `"Failed"` used for PersistentVolumes that failed to be correctly recycled or deleted after being released from a claim
   *  - `"Pending"` used for PersistentVolumes that are not available
   *  - `"Released"` used for PersistentVolumes where the bound PersistentVolumeClaim was deleted released volumes must be recycled before becoming available again this phase is used by the persistent volume claim binder to signal to another process to reclaim the resource
   */
  phase?: "Available" | "Bound" | "Failed" | "Pending" | "Released";
  /**
   * reason is a brief CamelCase string that describes any failure and is meant for machine parsing and tidy display in the CLI.
   */
  reason?: string;
  [k: string]: unknown;
}
/**
 * PersistentVolumeClaim is a user's request for and claim to a persistent volume
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeClaim".
 */
export interface IoK8SApiCoreV1145 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * spec defines the desired characteristics of a volume requested by a pod author. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#persistentvolumeclaims
   */
  spec?: IoK8SApiCoreV187;
  /**
   * status represents the current information/status of a persistent volume claim. Read-only. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#persistentvolumeclaims
   */
  status?: IoK8SApiCoreV1146;
  [k: string]: unknown;
}
/**
 * PersistentVolumeClaimStatus is the current status of a persistent volume claim.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeClaimStatus".
 */
export interface IoK8SApiCoreV1146 {
  /**
   * accessModes contains the actual access modes the volume backing the PVC has. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#access-modes-1
   */
  accessModes?: ("ReadOnlyMany" | "ReadWriteMany" | "ReadWriteOnce" | "ReadWriteOncePod")[];
  /**
   * allocatedResourceStatuses stores status of resource being resized for the given PVC. Key names follow standard Kubernetes label syntax. Valid values are either:
   * 	* Un-prefixed keys:
   * 		- storage - the capacity of the volume.
   * 	* Custom resources must use implementation-defined prefixed names such as "example.com/my-custom-resource"
   * Apart from above values - keys that are unprefixed or have kubernetes.io prefix are considered reserved and hence may not be used.
   *
   * ClaimResourceStatus can be in any of following states:
   * 	- ControllerResizeInProgress:
   * 		State set when resize controller starts resizing the volume in control-plane.
   * 	- ControllerResizeFailed:
   * 		State set when resize has failed in resize controller with a terminal error.
   * 	- NodeResizePending:
   * 		State set when resize controller has finished resizing the volume but further resizing of
   * 		volume is needed on the node.
   * 	- NodeResizeInProgress:
   * 		State set when kubelet starts resizing the volume.
   * 	- NodeResizeFailed:
   * 		State set when resizing has failed in kubelet with a terminal error. Transient errors don't set
   * 		NodeResizeFailed.
   * For example: if expanding a PVC for more capacity - this field can be one of the following states:
   * 	- pvc.status.allocatedResourceStatus['storage'] = "ControllerResizeInProgress"
   *      - pvc.status.allocatedResourceStatus['storage'] = "ControllerResizeFailed"
   *      - pvc.status.allocatedResourceStatus['storage'] = "NodeResizePending"
   *      - pvc.status.allocatedResourceStatus['storage'] = "NodeResizeInProgress"
   *      - pvc.status.allocatedResourceStatus['storage'] = "NodeResizeFailed"
   * When this field is not set, it means that no resize operation is in progress for the given PVC.
   *
   * A controller that receives PVC update with previously unknown resourceName or ClaimResourceStatus should ignore the update for the purpose it was designed. For example - a controller that only is responsible for resizing capacity of the volume, should ignore PVC updates that change other valid resources associated with PVC.
   *
   * This is an alpha field and requires enabling RecoverVolumeExpansionFailure feature.
   */
  allocatedResourceStatuses?: {
    [k: string]:
      | "ControllerResizeInProgress"
      | "ControllerResizeInfeasible"
      | "NodeResizeInProgress"
      | "NodeResizeInfeasible"
      | "NodeResizePending";
  };
  /**
   * allocatedResources tracks the resources allocated to a PVC including its capacity. Key names follow standard Kubernetes label syntax. Valid values are either:
   * 	* Un-prefixed keys:
   * 		- storage - the capacity of the volume.
   * 	* Custom resources must use implementation-defined prefixed names such as "example.com/my-custom-resource"
   * Apart from above values - keys that are unprefixed or have kubernetes.io prefix are considered reserved and hence may not be used.
   *
   * Capacity reported here may be larger than the actual capacity when a volume expansion operation is requested. For storage quota, the larger value from allocatedResources and PVC.spec.resources is used. If allocatedResources is not set, PVC.spec.resources alone is used for quota calculation. If a volume expansion capacity request is lowered, allocatedResources is only lowered if there are no expansion operations in progress and if the actual volume capacity is equal or lower than the requested capacity.
   *
   * A controller that receives PVC update with previously unknown resourceName should ignore the update for the purpose it was designed. For example - a controller that only is responsible for resizing capacity of the volume, should ignore PVC updates that change other valid resources associated with PVC.
   *
   * This is an alpha field and requires enabling RecoverVolumeExpansionFailure feature.
   */
  allocatedResources?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * capacity represents the actual resources of the underlying volume.
   */
  capacity?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * conditions is the current Condition of persistent volume claim. If underlying persistent volume is being resized then the Condition will be set to 'Resizing'.
   */
  conditions?: IoK8SApiCoreV1147[];
  /**
   * currentVolumeAttributesClassName is the current name of the VolumeAttributesClass the PVC is using. When unset, there is no VolumeAttributeClass applied to this PersistentVolumeClaim This is a beta field and requires enabling VolumeAttributesClass feature (off by default).
   */
  currentVolumeAttributesClassName?: string;
  /**
   * ModifyVolumeStatus represents the status object of ControllerModifyVolume operation. When this is unset, there is no ModifyVolume operation being attempted. This is a beta field and requires enabling VolumeAttributesClass feature (off by default).
   */
  modifyVolumeStatus?: IoK8SApiCoreV1115;
  /**
   * phase represents the current phase of PersistentVolumeClaim.
   *
   * Possible enum values:
   *  - `"Bound"` used for PersistentVolumeClaims that are bound
   *  - `"Lost"` used for PersistentVolumeClaims that lost their underlying PersistentVolume. The claim was bound to a PersistentVolume and this volume does not exist any longer and all data on it was lost.
   *  - `"Pending"` used for PersistentVolumeClaims that are not yet bound
   */
  phase?: "Bound" | "Lost" | "Pending";
  [k: string]: unknown;
}
/**
 * PersistentVolumeClaimCondition contains details about state of pvc
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeClaimCondition".
 */
export interface IoK8SApiCoreV1147 {
  /**
   * lastProbeTime is the time we probed the condition.
   */
  lastProbeTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * lastTransitionTime is the time the condition transitioned from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * message is the human-readable message indicating details about last transition.
   */
  message?: string;
  /**
   * reason is a unique, this should be a short, machine understandable string that gives the reason for condition's last transition. If it reports "Resizing" that means the underlying persistent volume is being resized.
   */
  reason?: string;
  /**
   * Status is the status of the condition. Can be True, False, Unknown. More info: https://kubernetes.io/docs/reference/kubernetes-api/config-and-storage-resources/persistent-volume-claim-v1/#:~:text=state%20of%20pvc-,conditions.status,-(string)%2C%20required
   */
  status: string;
  /**
   * Type is the type of the condition. More info: https://kubernetes.io/docs/reference/kubernetes-api/config-and-storage-resources/persistent-volume-claim-v1/#:~:text=set%20to%20%27ResizeStarted%27.-,PersistentVolumeClaimCondition,-contains%20details%20about
   */
  type: string;
  [k: string]: unknown;
}
/**
 * PersistentVolumeClaimList is a list of PersistentVolumeClaim items.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeClaimList".
 */
export interface IoK8SApiCoreV1PersistentVolumeClaimList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * items is a list of persistent volume claims. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#persistentvolumeclaims
   */
  items: IoK8SApiCoreV1145[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * PersistentVolumeClaimVolumeSource references the user's PVC in the same namespace. This volume finds the bound PV and mounts that volume for the pod. A PersistentVolumeClaimVolumeSource is, essentially, a wrapper around another type of volume that is owned by someone else (the system).
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeClaimVolumeSource".
 */
export interface IoK8SApiCoreV1148 {
  /**
   * claimName is the name of a PersistentVolumeClaim in the same namespace as the pod using this volume. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#persistentvolumeclaims
   */
  claimName: string;
  /**
   * readOnly Will force the ReadOnly setting in VolumeMounts. Default false.
   */
  readOnly?: boolean;
  [k: string]: unknown;
}
/**
 * PersistentVolumeList is a list of PersistentVolume items.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PersistentVolumeList".
 */
export interface IoK8SApiCoreV1PersistentVolumeList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * items is a list of persistent volumes. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes
   */
  items: IoK8SApiCoreV1134[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * Pod is a collection of containers that can run on a host. This resource is created by clients and scheduled onto hosts.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Pod".
 */
export interface IoK8SApiCoreV1149 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Specification of the desired behavior of the pod. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiCoreV1150;
  /**
   * Most recently observed status of the pod. This data may not be up to date. Populated by the system. Read-only. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiCoreV1170;
  [k: string]: unknown;
}
/**
 * PodSpec is a description of a pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodSpec".
 */
export interface IoK8SApiCoreV1150 {
  /**
   * Optional duration in seconds the pod may be active on the node relative to StartTime before the system will actively try to mark it failed and kill associated containers. Value must be a positive integer.
   */
  activeDeadlineSeconds?: number;
  /**
   * If specified, the pod's scheduling constraints
   */
  affinity?: IoK8SApiCoreV11;
  /**
   * AutomountServiceAccountToken indicates whether a service account token should be automatically mounted.
   */
  automountServiceAccountToken?: boolean;
  /**
   * List of containers belonging to the pod. Containers cannot currently be added or removed. There must be at least one container in a Pod. Cannot be updated.
   */
  containers: IoK8SApiCoreV137[];
  /**
   * Specifies the DNS parameters of a pod. Parameters specified here will be merged to the generated DNS configuration based on DNSPolicy.
   */
  dnsConfig?: IoK8SApiCoreV1151;
  /**
   * Set DNS policy for the pod. Defaults to "ClusterFirst". Valid values are 'ClusterFirstWithHostNet', 'ClusterFirst', 'Default' or 'None'. DNS parameters given in DNSConfig will be merged with the policy selected with DNSPolicy. To have DNS options set along with hostNetwork, you have to specify DNS policy explicitly to 'ClusterFirstWithHostNet'.
   *
   * Possible enum values:
   *  - `"ClusterFirst"` indicates that the pod should use cluster DNS first unless hostNetwork is true, if it is available, then fall back on the default (as determined by kubelet) DNS settings.
   *  - `"ClusterFirstWithHostNet"` indicates that the pod should use cluster DNS first, if it is available, then fall back on the default (as determined by kubelet) DNS settings.
   *  - `"Default"` indicates that the pod should use the default (as determined by kubelet) DNS settings.
   *  - `"None"` indicates that the pod should use empty DNS settings. DNS parameters such as nameservers and search paths should be defined via DNSConfig.
   */
  dnsPolicy?: "ClusterFirst" | "ClusterFirstWithHostNet" | "Default" | "None";
  /**
   * EnableServiceLinks indicates whether information about services should be injected into pod's environment variables, matching the syntax of Docker links. Optional: Defaults to true.
   */
  enableServiceLinks?: boolean;
  /**
   * List of ephemeral containers run in this pod. Ephemeral containers may be run in an existing pod to perform user-initiated actions such as debugging. This list cannot be specified when creating a pod, and it cannot be modified by updating the pod spec. In order to add an ephemeral container to an existing pod, use the pod's ephemeralcontainers subresource.
   */
  ephemeralContainers?: IoK8SApiCoreV184[];
  /**
   * HostAliases is an optional list of hosts and IPs that will be injected into the pod's hosts file if specified.
   */
  hostAliases?: IoK8SApiCoreV1102[];
  /**
   * Use the host's ipc namespace. Optional: Default to false.
   */
  hostIPC?: boolean;
  /**
   * Host networking requested for this pod. Use the host's network namespace. If this option is set, the ports that will be used must be specified. Default to false.
   */
  hostNetwork?: boolean;
  /**
   * Use the host's pid namespace. Optional: Default to false.
   */
  hostPID?: boolean;
  /**
   * Use the host's user namespace. Optional: Default to true. If set to true or not present, the pod will be run in the host user namespace, useful for when the pod needs a feature only available to the host user namespace, such as loading a kernel module with CAP_SYS_MODULE. When set to false, a new userns is created for the pod. Setting false is useful for mitigating container breakout vulnerabilities even allowing users to run their containers as root without actually having root privileges on the host. This field is alpha-level and is only honored by servers that enable the UserNamespacesSupport feature.
   */
  hostUsers?: boolean;
  /**
   * Specifies the hostname of the Pod If not specified, the pod's hostname will be set to a system-defined value.
   */
  hostname?: string;
  /**
   * ImagePullSecrets is an optional list of references to secrets in the same namespace to use for pulling any of the images used by this PodSpec. If specified, these secrets will be passed to individual puller implementations for them to use. More info: https://kubernetes.io/docs/concepts/containers/images#specifying-imagepullsecrets-on-a-pod
   */
  imagePullSecrets?: IoK8SApiCoreV120[];
  /**
   * List of initialization containers belonging to the pod. Init containers are executed in order prior to containers being started. If any init container fails, the pod is considered to have failed and is handled according to its restartPolicy. The name for an init container or normal container must be unique among all containers. Init containers may not have Lifecycle actions, Readiness probes, Liveness probes, or Startup probes. The resourceRequirements of an init container are taken into account during scheduling by finding the highest request/limit for each resource type, and then using the max of of that value or the sum of the normal containers. Limits are applied to init containers in a similar fashion. Init containers cannot currently be added or removed. Cannot be updated. More info: https://kubernetes.io/docs/concepts/workloads/pods/init-containers/
   */
  initContainers?: IoK8SApiCoreV137[];
  /**
   * NodeName indicates in which node this pod is scheduled. If empty, this pod is a candidate for scheduling by the scheduler defined in schedulerName. Once this field is set, the kubelet for this node becomes responsible for the lifecycle of this pod. This field should not be used to express a desire for the pod to be scheduled on a specific node. https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/#nodename
   */
  nodeName?: string;
  /**
   * NodeSelector is a selector which must be true for the pod to fit on a node. Selector which must match a node's labels for the pod to be scheduled on that node. More info: https://kubernetes.io/docs/concepts/configuration/assign-pod-node/
   */
  nodeSelector?: {
    [k: string]: string;
  };
  /**
   * Specifies the OS of the containers in the pod. Some pod and container fields are restricted if this is set.
   *
   * If the OS field is set to linux, the following fields must be unset: -securityContext.windowsOptions
   *
   * If the OS field is set to windows, following fields must be unset: - spec.hostPID - spec.hostIPC - spec.hostUsers - spec.securityContext.appArmorProfile - spec.securityContext.seLinuxOptions - spec.securityContext.seccompProfile - spec.securityContext.fsGroup - spec.securityContext.fsGroupChangePolicy - spec.securityContext.sysctls - spec.shareProcessNamespace - spec.securityContext.runAsUser - spec.securityContext.runAsGroup - spec.securityContext.supplementalGroups - spec.securityContext.supplementalGroupsPolicy - spec.containers[*].securityContext.appArmorProfile - spec.containers[*].securityContext.seLinuxOptions - spec.containers[*].securityContext.seccompProfile - spec.containers[*].securityContext.capabilities - spec.containers[*].securityContext.readOnlyRootFilesystem - spec.containers[*].securityContext.privileged - spec.containers[*].securityContext.allowPrivilegeEscalation - spec.containers[*].securityContext.procMount - spec.containers[*].securityContext.runAsUser - spec.containers[*].securityContext.runAsGroup
   */
  os?: IoK8SApiCoreV1153;
  /**
   * Overhead represents the resource overhead associated with running a pod for a given RuntimeClass. This field will be autopopulated at admission time by the RuntimeClass admission controller. If the RuntimeClass admission controller is enabled, overhead must not be set in Pod create requests. The RuntimeClass admission controller will reject Pod create requests which have the overhead already set. If RuntimeClass is configured and selected in the PodSpec, Overhead will be set to the value defined in the corresponding RuntimeClass, otherwise it will remain unset and treated as zero. More info: https://git.k8s.io/enhancements/keps/sig-node/688-pod-overhead/README.md
   */
  overhead?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * PreemptionPolicy is the Policy for preempting pods with lower priority. One of Never, PreemptLowerPriority. Defaults to PreemptLowerPriority if unset.
   *
   * Possible enum values:
   *  - `"Never"` means that pod never preempts other pods with lower priority.
   *  - `"PreemptLowerPriority"` means that pod can preempt other pods with lower priority.
   */
  preemptionPolicy?: "Never" | "PreemptLowerPriority";
  /**
   * The priority value. Various system components use this field to find the priority of the pod. When Priority Admission Controller is enabled, it prevents users from setting this field. The admission controller populates this field from PriorityClassName. The higher the value, the higher the priority.
   */
  priority?: number;
  /**
   * If specified, indicates the pod's priority. "system-node-critical" and "system-cluster-critical" are two special keywords which indicate the highest priorities with the former being the highest priority. Any other name must be defined by creating a PriorityClass object with that name. If not specified, the pod priority will be default or zero if there is no default.
   */
  priorityClassName?: string;
  /**
   * If specified, all readiness gates will be evaluated for pod readiness. A pod is ready when all its containers are ready AND all conditions specified in the readiness gates have status equal to "True" More info: https://git.k8s.io/enhancements/keps/sig-network/580-pod-readiness-gates
   */
  readinessGates?: IoK8SApiCoreV1154[];
  /**
   * ResourceClaims defines which ResourceClaims must be allocated and reserved before the Pod is allowed to start. The resources will be made available to those containers which consume them by name.
   *
   * This is an alpha field and requires enabling the DynamicResourceAllocation feature gate.
   *
   * This field is immutable.
   */
  resourceClaims?: IoK8SApiCoreV1155[];
  /**
   * Resources is the total amount of CPU and Memory resources required by all containers in the pod. It supports specifying Requests and Limits for "cpu" and "memory" resource names only. ResourceClaims are not supported.
   *
   * This field enables fine-grained control over resource allocation for the entire pod, allowing resource sharing among containers in a pod.
   *
   * This is an alpha field and requires enabling the PodLevelResources feature gate.
   */
  resources?: IoK8SApiCoreV156;
  /**
   * Restart policy for all containers within the pod. One of Always, OnFailure, Never. In some contexts, only a subset of those values may be permitted. Default to Always. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#restart-policy
   *
   * Possible enum values:
   *  - `"Always"`
   *  - `"Never"`
   *  - `"OnFailure"`
   */
  restartPolicy?: "Always" | "Never" | "OnFailure";
  /**
   * RuntimeClassName refers to a RuntimeClass object in the node.k8s.io group, which should be used to run this pod.  If no RuntimeClass resource matches the named class, the pod will not be run. If unset or empty, the "legacy" RuntimeClass will be used, which is an implicit class with an empty definition that uses the default runtime handler. More info: https://git.k8s.io/enhancements/keps/sig-node/585-runtime-class
   */
  runtimeClassName?: string;
  /**
   * If specified, the pod will be dispatched by specified scheduler. If not specified, the pod will be dispatched by default scheduler.
   */
  schedulerName?: string;
  /**
   * SchedulingGates is an opaque list of values that if specified will block scheduling the pod. If schedulingGates is not empty, the pod will stay in the SchedulingGated state and the scheduler will not attempt to schedule the pod.
   *
   * SchedulingGates can only be set at pod creation time, and be removed only afterwards.
   */
  schedulingGates?: IoK8SApiCoreV1156[];
  /**
   * SecurityContext holds pod-level security attributes and common container settings. Optional: Defaults to empty.  See type description for default values of each field.
   */
  securityContext?: IoK8SApiCoreV1157;
  /**
   * DeprecatedServiceAccount is a deprecated alias for ServiceAccountName. Deprecated: Use serviceAccountName instead.
   */
  serviceAccount?: string;
  /**
   * ServiceAccountName is the name of the ServiceAccount to use to run this pod. More info: https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/
   */
  serviceAccountName?: string;
  /**
   * If true the pod's hostname will be configured as the pod's FQDN, rather than the leaf name (the default). In Linux containers, this means setting the FQDN in the hostname field of the kernel (the nodename field of struct utsname). In Windows containers, this means setting the registry value of hostname for the registry key HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\Tcpip\\Parameters to FQDN. If a pod does not have FQDN, this has no effect. Default to false.
   */
  setHostnameAsFQDN?: boolean;
  /**
   * Share a single process namespace between all of the containers in a pod. When this is set containers will be able to view and signal processes from other containers in the same pod, and the first process in each container will not be assigned PID 1. HostPID and ShareProcessNamespace cannot both be set. Optional: Default to false.
   */
  shareProcessNamespace?: boolean;
  /**
   * If specified, the fully qualified Pod hostname will be "<hostname>.<subdomain>.<pod namespace>.svc.<cluster domain>". If not specified, the pod will not have a domainname at all.
   */
  subdomain?: string;
  /**
   * Optional duration in seconds the pod needs to terminate gracefully. May be decreased in delete request. Value must be non-negative integer. The value zero indicates stop immediately via the kill signal (no opportunity to shut down). If this value is nil, the default grace period will be used instead. The grace period is the duration in seconds after the processes running in the pod are sent a termination signal and the time when the processes are forcibly halted with a kill signal. Set this value longer than the expected cleanup time for your process. Defaults to 30 seconds.
   */
  terminationGracePeriodSeconds?: number;
  /**
   * If specified, the pod's tolerations.
   */
  tolerations?: IoK8SApiCoreV1159[];
  /**
   * TopologySpreadConstraints describes how a group of pods ought to spread across topology domains. Scheduler will schedule pods in a way which abides by the constraints. All topologySpreadConstraints are ANDed.
   */
  topologySpreadConstraints?: IoK8SApiCoreV1160[];
  /**
   * List of volumes that can be mounted by containers belonging to the pod. More info: https://kubernetes.io/docs/concepts/storage/volumes
   */
  volumes?: IoK8SApiCoreV1161[];
  [k: string]: unknown;
}
/**
 * PodDNSConfig defines the DNS parameters of a pod in addition to those generated from DNSPolicy.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodDNSConfig".
 */
export interface IoK8SApiCoreV1151 {
  /**
   * A list of DNS name server IP addresses. This will be appended to the base nameservers generated from DNSPolicy. Duplicated nameservers will be removed.
   */
  nameservers?: string[];
  /**
   * A list of DNS resolver options. This will be merged with the base options generated from DNSPolicy. Duplicated entries will be removed. Resolution options given in Options will override those that appear in the base DNSPolicy.
   */
  options?: IoK8SApiCoreV1152[];
  /**
   * A list of DNS search domains for host-name lookup. This will be appended to the base search paths generated from DNSPolicy. Duplicated search paths will be removed.
   */
  searches?: string[];
  [k: string]: unknown;
}
/**
 * PodDNSConfigOption defines DNS resolver options of a pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodDNSConfigOption".
 */
export interface IoK8SApiCoreV1152 {
  /**
   * Name is this DNS resolver option's name. Required.
   */
  name?: string;
  /**
   * Value is this DNS resolver option's value.
   */
  value?: string;
  [k: string]: unknown;
}
/**
 * PodOS defines the OS parameters of a pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodOS".
 */
export interface IoK8SApiCoreV1153 {
  /**
   * Name is the name of the operating system. The currently supported values are linux and windows. Additional value may be defined in future and can be one of: https://github.com/opencontainers/runtime-spec/blob/master/config.md#platform-specific-configuration Clients should expect to handle additional values and treat unrecognized values in this field as os: null
   */
  name: string;
  [k: string]: unknown;
}
/**
 * PodReadinessGate contains the reference to a pod condition
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodReadinessGate".
 */
export interface IoK8SApiCoreV1154 {
  /**
   * ConditionType refers to a condition in the pod's condition list with matching type.
   */
  conditionType: string;
  [k: string]: unknown;
}
/**
 * PodResourceClaim references exactly one ResourceClaim, either directly or by naming a ResourceClaimTemplate which is then turned into a ResourceClaim for the pod.
 *
 * It adds a name to it that uniquely identifies the ResourceClaim inside the Pod. Containers that need access to the ResourceClaim reference it with this name.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodResourceClaim".
 */
export interface IoK8SApiCoreV1155 {
  /**
   * Name uniquely identifies this resource claim inside the pod. This must be a DNS_LABEL.
   */
  name: string;
  /**
   * ResourceClaimName is the name of a ResourceClaim object in the same namespace as this pod.
   *
   * Exactly one of ResourceClaimName and ResourceClaimTemplateName must be set.
   */
  resourceClaimName?: string;
  /**
   * ResourceClaimTemplateName is the name of a ResourceClaimTemplate object in the same namespace as this pod.
   *
   * The template will be used to create a new ResourceClaim, which will be bound to this pod. When this pod is deleted, the ResourceClaim will also be deleted. The pod name and resource name, along with a generated component, will be used to form a unique name for the ResourceClaim, which will be recorded in pod.status.resourceClaimStatuses.
   *
   * This field is immutable and no changes will be made to the corresponding ResourceClaim by the control plane after creating the ResourceClaim.
   *
   * Exactly one of ResourceClaimName and ResourceClaimTemplateName must be set.
   */
  resourceClaimTemplateName?: string;
  [k: string]: unknown;
}
/**
 * PodSchedulingGate is associated to a Pod to guard its scheduling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodSchedulingGate".
 */
export interface IoK8SApiCoreV1156 {
  /**
   * Name of the scheduling gate. Each scheduling gate must have a unique name field.
   */
  name: string;
  [k: string]: unknown;
}
/**
 * PodSecurityContext holds pod-level security attributes and common container settings. Some fields are also present in container.securityContext.  Field values of container.securityContext take precedence over field values of PodSecurityContext.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodSecurityContext".
 */
export interface IoK8SApiCoreV1157 {
  /**
   * appArmorProfile is the AppArmor options to use by the containers in this pod. Note that this field cannot be set when spec.os.name is windows.
   */
  appArmorProfile?: IoK8SApiCoreV111;
  /**
   * A special supplemental group that applies to all containers in a pod. Some volume types allow the Kubelet to change the ownership of that volume to be owned by the pod:
   *
   * 1. The owning GID will be the FSGroup 2. The setgid bit is set (new files created in the volume will be owned by FSGroup) 3. The permission bits are OR'd with rw-rw----
   *
   * If unset, the Kubelet will not modify the ownership and permissions of any volume. Note that this field cannot be set when spec.os.name is windows.
   */
  fsGroup?: number;
  /**
   * fsGroupChangePolicy defines behavior of changing ownership and permission of the volume before being exposed inside Pod. This field will only apply to volume types which support fsGroup based ownership(and permissions). It will have no effect on ephemeral volume types such as: secret, configmaps and emptydir. Valid values are "OnRootMismatch" and "Always". If not specified, "Always" is used. Note that this field cannot be set when spec.os.name is windows.
   *
   * Possible enum values:
   *  - `"Always"` indicates that volume's ownership and permissions should always be changed whenever volume is mounted inside a Pod. This the default behavior.
   *  - `"OnRootMismatch"` indicates that volume's ownership and permissions will be changed only when permission and ownership of root directory does not match with expected permissions on the volume. This can help shorten the time it takes to change ownership and permissions of a volume.
   */
  fsGroupChangePolicy?: "Always" | "OnRootMismatch";
  /**
   * The GID to run the entrypoint of the container process. Uses runtime default if unset. May also be set in SecurityContext.  If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence for that container. Note that this field cannot be set when spec.os.name is windows.
   */
  runAsGroup?: number;
  /**
   * Indicates that the container must run as a non-root user. If true, the Kubelet will validate the image at runtime to ensure that it does not run as UID 0 (root) and fail to start the container if it does. If unset or false, no such validation will be performed. May also be set in SecurityContext.  If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence.
   */
  runAsNonRoot?: boolean;
  /**
   * The UID to run the entrypoint of the container process. Defaults to user specified in image metadata if unspecified. May also be set in SecurityContext.  If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence for that container. Note that this field cannot be set when spec.os.name is windows.
   */
  runAsUser?: number;
  /**
   * seLinuxChangePolicy defines how the container's SELinux label is applied to all volumes used by the Pod. It has no effect on nodes that do not support SELinux or to volumes does not support SELinux. Valid values are "MountOption" and "Recursive".
   *
   * "Recursive" means relabeling of all files on all Pod volumes by the container runtime. This may be slow for large volumes, but allows mixing privileged and unprivileged Pods sharing the same volume on the same node.
   *
   * "MountOption" mounts all eligible Pod volumes with `-o context` mount option. This requires all Pods that share the same volume to use the same SELinux label. It is not possible to share the same volume among privileged and unprivileged Pods. Eligible volumes are in-tree FibreChannel and iSCSI volumes, and all CSI volumes whose CSI driver announces SELinux support by setting spec.seLinuxMount: true in their CSIDriver instance. Other volumes are always re-labelled recursively. "MountOption" value is allowed only when SELinuxMount feature gate is enabled.
   *
   * If not specified and SELinuxMount feature gate is enabled, "MountOption" is used. If not specified and SELinuxMount feature gate is disabled, "MountOption" is used for ReadWriteOncePod volumes and "Recursive" for all other volumes.
   *
   * This field affects only Pods that have SELinux label set, either in PodSecurityContext or in SecurityContext of all containers.
   *
   * All Pods that use the same volume should use the same seLinuxChangePolicy, otherwise some pods can get stuck in ContainerCreating state. Note that this field cannot be set when spec.os.name is windows.
   */
  seLinuxChangePolicy?: string;
  /**
   * The SELinux context to be applied to all containers. If unspecified, the container runtime will allocate a random SELinux context for each container.  May also be set in SecurityContext.  If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence for that container. Note that this field cannot be set when spec.os.name is windows.
   */
  seLinuxOptions?: IoK8SApiCoreV159;
  /**
   * The seccomp options to use by the containers in this pod. Note that this field cannot be set when spec.os.name is windows.
   */
  seccompProfile?: IoK8SApiCoreV160;
  /**
   * A list of groups applied to the first process run in each container, in addition to the container's primary GID and fsGroup (if specified).  If the SupplementalGroupsPolicy feature is enabled, the supplementalGroupsPolicy field determines whether these are in addition to or instead of any group memberships defined in the container image. If unspecified, no additional groups are added, though group memberships defined in the container image may still be used, depending on the supplementalGroupsPolicy field. Note that this field cannot be set when spec.os.name is windows.
   */
  supplementalGroups?: number[];
  /**
   * Defines how supplemental groups of the first container processes are calculated. Valid values are "Merge" and "Strict". If not specified, "Merge" is used. (Alpha) Using the field requires the SupplementalGroupsPolicy feature gate to be enabled and the container runtime must implement support for this feature. Note that this field cannot be set when spec.os.name is windows.
   *
   * Possible enum values:
   *  - `"Merge"` means that the container's provided SupplementalGroups and FsGroup (specified in SecurityContext) will be merged with the primary user's groups as defined in the container image (in /etc/group).
   *  - `"Strict"` means that the container's provided SupplementalGroups and FsGroup (specified in SecurityContext) will be used instead of any groups defined in the container image.
   */
  supplementalGroupsPolicy?: "Merge" | "Strict";
  /**
   * Sysctls hold a list of namespaced sysctls used for the pod. Pods with unsupported sysctls (by the container runtime) might fail to launch. Note that this field cannot be set when spec.os.name is windows.
   */
  sysctls?: IoK8SApiCoreV1158[];
  /**
   * The Windows specific settings applied to all containers. If unspecified, the options within a container's SecurityContext will be used. If set in both SecurityContext and PodSecurityContext, the value specified in SecurityContext takes precedence. Note that this field cannot be set when spec.os.name is linux.
   */
  windowsOptions?: IoK8SApiCoreV161;
  [k: string]: unknown;
}
/**
 * Sysctl defines a kernel parameter to be set
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Sysctl".
 */
export interface IoK8SApiCoreV1158 {
  /**
   * Name of a property to set
   */
  name: string;
  /**
   * Value of a property to set
   */
  value: string;
  [k: string]: unknown;
}
/**
 * The pod this Toleration is attached to tolerates any taint that matches the triple <key,value,effect> using the matching operator <operator>.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Toleration".
 */
export interface IoK8SApiCoreV1159 {
  /**
   * Effect indicates the taint effect to match. Empty means match all taint effects. When specified, allowed values are NoSchedule, PreferNoSchedule and NoExecute.
   *
   * Possible enum values:
   *  - `"NoExecute"` Evict any already-running pods that do not tolerate the taint. Currently enforced by NodeController.
   *  - `"NoSchedule"` Do not allow new pods to schedule onto the node unless they tolerate the taint, but allow all pods submitted to Kubelet without going through the scheduler to start, and allow all already-running pods to continue running. Enforced by the scheduler.
   *  - `"PreferNoSchedule"` Like TaintEffectNoSchedule, but the scheduler tries not to schedule new pods onto the node, rather than prohibiting new pods from scheduling onto the node entirely. Enforced by the scheduler.
   */
  effect?: "NoExecute" | "NoSchedule" | "PreferNoSchedule";
  /**
   * Key is the taint key that the toleration applies to. Empty means match all taint keys. If the key is empty, operator must be Exists; this combination means to match all values and all keys.
   */
  key?: string;
  /**
   * Operator represents a key's relationship to the value. Valid operators are Exists and Equal. Defaults to Equal. Exists is equivalent to wildcard for value, so that a pod can tolerate all taints of a particular category.
   *
   * Possible enum values:
   *  - `"Equal"`
   *  - `"Exists"`
   */
  operator?: "Equal" | "Exists";
  /**
   * TolerationSeconds represents the period of time the toleration (which must be of effect NoExecute, otherwise this field is ignored) tolerates the taint. By default, it is not set, which means tolerate the taint forever (do not evict). Zero and negative values will be treated as 0 (evict immediately) by the system.
   */
  tolerationSeconds?: number;
  /**
   * Value is the taint value the toleration matches to. If the operator is Exists, the value should be empty, otherwise just a regular string.
   */
  value?: string;
  [k: string]: unknown;
}
/**
 * TopologySpreadConstraint specifies how to spread matching pods among the given topology.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.TopologySpreadConstraint".
 */
export interface IoK8SApiCoreV1160 {
  /**
   * LabelSelector is used to find matching pods. Pods that match this label selector are counted to determine the number of pods in their corresponding topology domain.
   */
  labelSelector?: IoK8SApimachineryPkgApisMetaV14;
  /**
   * MatchLabelKeys is a set of pod label keys to select the pods over which spreading will be calculated. The keys are used to lookup values from the incoming pod labels, those key-value labels are ANDed with labelSelector to select the group of existing pods over which spreading will be calculated for the incoming pod. The same key is forbidden to exist in both MatchLabelKeys and LabelSelector. MatchLabelKeys cannot be set when LabelSelector isn't set. Keys that don't exist in the incoming pod labels will be ignored. A null or empty list means only match against labelSelector.
   *
   * This is a beta field and requires the MatchLabelKeysInPodTopologySpread feature gate to be enabled (enabled by default).
   */
  matchLabelKeys?: string[];
  /**
   * MaxSkew describes the degree to which pods may be unevenly distributed. When `whenUnsatisfiable=DoNotSchedule`, it is the maximum permitted difference between the number of matching pods in the target topology and the global minimum. The global minimum is the minimum number of matching pods in an eligible domain or zero if the number of eligible domains is less than MinDomains. For example, in a 3-zone cluster, MaxSkew is set to 1, and pods with the same labelSelector spread as 2/2/1: In this case, the global minimum is 1. | zone1 | zone2 | zone3 | |  P P  |  P P  |   P   | - if MaxSkew is 1, incoming pod can only be scheduled to zone3 to become 2/2/2; scheduling it onto zone1(zone2) would make the ActualSkew(3-1) on zone1(zone2) violate MaxSkew(1). - if MaxSkew is 2, incoming pod can be scheduled onto any zone. When `whenUnsatisfiable=ScheduleAnyway`, it is used to give higher precedence to topologies that satisfy it. It's a required field. Default value is 1 and 0 is not allowed.
   */
  maxSkew: number;
  /**
   * MinDomains indicates a minimum number of eligible domains. When the number of eligible domains with matching topology keys is less than minDomains, Pod Topology Spread treats "global minimum" as 0, and then the calculation of Skew is performed. And when the number of eligible domains with matching topology keys equals or greater than minDomains, this value has no effect on scheduling. As a result, when the number of eligible domains is less than minDomains, scheduler won't schedule more than maxSkew Pods to those domains. If value is nil, the constraint behaves as if MinDomains is equal to 1. Valid values are integers greater than 0. When value is not nil, WhenUnsatisfiable must be DoNotSchedule.
   *
   * For example, in a 3-zone cluster, MaxSkew is set to 2, MinDomains is set to 5 and pods with the same labelSelector spread as 2/2/2: | zone1 | zone2 | zone3 | |  P P  |  P P  |  P P  | The number of domains is less than 5(MinDomains), so "global minimum" is treated as 0. In this situation, new pod with the same labelSelector cannot be scheduled, because computed skew will be 3(3 - 0) if new Pod is scheduled to any of the three zones, it will violate MaxSkew.
   */
  minDomains?: number;
  /**
   * NodeAffinityPolicy indicates how we will treat Pod's nodeAffinity/nodeSelector when calculating pod topology spread skew. Options are: - Honor: only nodes matching nodeAffinity/nodeSelector are included in the calculations. - Ignore: nodeAffinity/nodeSelector are ignored. All nodes are included in the calculations.
   *
   * If this value is nil, the behavior is equivalent to the Honor policy. This is a beta-level feature default enabled by the NodeInclusionPolicyInPodTopologySpread feature flag.
   *
   * Possible enum values:
   *  - `"Honor"` means use this scheduling directive when calculating pod topology spread skew.
   *  - `"Ignore"` means ignore this scheduling directive when calculating pod topology spread skew.
   */
  nodeAffinityPolicy?: "Honor" | "Ignore";
  /**
   * NodeTaintsPolicy indicates how we will treat node taints when calculating pod topology spread skew. Options are: - Honor: nodes without taints, along with tainted nodes for which the incoming pod has a toleration, are included. - Ignore: node taints are ignored. All nodes are included.
   *
   * If this value is nil, the behavior is equivalent to the Ignore policy. This is a beta-level feature default enabled by the NodeInclusionPolicyInPodTopologySpread feature flag.
   *
   * Possible enum values:
   *  - `"Honor"` means use this scheduling directive when calculating pod topology spread skew.
   *  - `"Ignore"` means ignore this scheduling directive when calculating pod topology spread skew.
   */
  nodeTaintsPolicy?: "Honor" | "Ignore";
  /**
   * TopologyKey is the key of node labels. Nodes that have a label with this key and identical values are considered to be in the same topology. We consider each <key, value> as a "bucket", and try to put balanced number of pods into each bucket. We define a domain as a particular instance of a topology. Also, we define an eligible domain as a domain whose nodes meet the requirements of nodeAffinityPolicy and nodeTaintsPolicy. e.g. If TopologyKey is "kubernetes.io/hostname", each Node is a domain of that topology. And, if TopologyKey is "topology.kubernetes.io/zone", each zone is a domain of that topology. It's a required field.
   */
  topologyKey: string;
  /**
   * WhenUnsatisfiable indicates how to deal with a pod if it doesn't satisfy the spread constraint. - DoNotSchedule (default) tells the scheduler not to schedule it. - ScheduleAnyway tells the scheduler to schedule the pod in any location,
   *   but giving higher precedence to topologies that would help reduce the
   *   skew.
   * A constraint is considered "Unsatisfiable" for an incoming pod if and only if every possible node assignment for that pod would violate "MaxSkew" on some topology. For example, in a 3-zone cluster, MaxSkew is set to 1, and pods with the same labelSelector spread as 3/1/1: | zone1 | zone2 | zone3 | | P P P |   P   |   P   | If WhenUnsatisfiable is set to DoNotSchedule, incoming pod can only be scheduled to zone2(zone3) to become 3/2/1(3/1/2) as ActualSkew(2-1) on zone2(zone3) satisfies MaxSkew(1). In other words, the cluster can still be imbalanced, but scheduler won't make it *more* imbalanced. It's a required field.
   *
   * Possible enum values:
   *  - `"DoNotSchedule"` instructs the scheduler not to schedule the pod when constraints are not satisfied.
   *  - `"ScheduleAnyway"` instructs the scheduler to schedule the pod even if constraints are not satisfied.
   */
  whenUnsatisfiable: "DoNotSchedule" | "ScheduleAnyway";
  [k: string]: unknown;
}
/**
 * Volume represents a named volume in a pod that may be accessed by any container in the pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Volume".
 */
export interface IoK8SApiCoreV1161 {
  /**
   * awsElasticBlockStore represents an AWS Disk resource that is attached to a kubelet's host machine and then exposed to the pod. Deprecated: AWSElasticBlockStore is deprecated. All operations for the in-tree awsElasticBlockStore type are redirected to the ebs.csi.aws.com CSI driver. More info: https://kubernetes.io/docs/concepts/storage/volumes#awselasticblockstore
   */
  awsElasticBlockStore?: IoK8SApiCoreV1;
  /**
   * azureDisk represents an Azure Data Disk mount on the host and bind mount to the pod. Deprecated: AzureDisk is deprecated. All operations for the in-tree azureDisk type are redirected to the disk.csi.azure.com CSI driver.
   */
  azureDisk?: IoK8SApiCoreV113;
  /**
   * azureFile represents an Azure File Service mount on the host and bind mount to the pod. Deprecated: AzureFile is deprecated. All operations for the in-tree azureFile type are redirected to the file.csi.azure.com CSI driver.
   */
  azureFile?: IoK8SApiCoreV115;
  /**
   * cephFS represents a Ceph FS mount on the host that shares a pod's lifetime. Deprecated: CephFS is deprecated and the in-tree cephfs type is no longer supported.
   */
  cephfs?: IoK8SApiCoreV123;
  /**
   * cinder represents a cinder volume attached and mounted on kubelets host machine. Deprecated: Cinder is deprecated. All operations for the in-tree cinder type are redirected to the cinder.csi.openstack.org CSI driver. More info: https://examples.k8s.io/mysql-cinder-pd/README.md
   */
  cinder?: IoK8SApiCoreV125;
  /**
   * configMap represents a configMap that should populate this volume
   */
  configMap?: IoK8SApiCoreV136;
  /**
   * csi (Container Storage Interface) represents ephemeral storage that is handled by certain external CSI drivers.
   */
  csi?: IoK8SApiCoreV119;
  /**
   * downwardAPI represents downward API about the pod that should populate this volume
   */
  downwardAPI?: IoK8SApiCoreV178;
  /**
   * emptyDir represents a temporary directory that shares a pod's lifetime. More info: https://kubernetes.io/docs/concepts/storage/volumes#emptydir
   */
  emptyDir?: IoK8SApiCoreV179;
  /**
   * ephemeral represents a volume that is handled by a cluster storage driver. The volume's lifecycle is tied to the pod that defines it - it will be created before the pod starts, and deleted when the pod is removed.
   *
   * Use this if: a) the volume is only needed while the pod runs, b) features of normal volumes like restoring from snapshot or capacity
   *    tracking are needed,
   * c) the storage driver is specified through a storage class, and d) the storage driver supports dynamic volume provisioning through
   *    a PersistentVolumeClaim (see EphemeralVolumeSource for more
   *    information on the connection between this volume type
   *    and PersistentVolumeClaim).
   *
   * Use PersistentVolumeClaim or one of the vendor-specific APIs for volumes that persist for longer than the lifecycle of an individual pod.
   *
   * Use CSI for light-weight local ephemeral volumes if the CSI driver is meant to be used that way - see the documentation of the driver for more information.
   *
   * A pod can use both types of ephemeral volumes and persistent volumes at the same time.
   */
  ephemeral?: IoK8SApiCoreV185;
  /**
   * fc represents a Fibre Channel resource that is attached to a kubelet's host machine and then exposed to the pod.
   */
  fc?: IoK8SApiCoreV194;
  /**
   * flexVolume represents a generic volume resource that is provisioned/attached using an exec based plugin. Deprecated: FlexVolume is deprecated. Consider using a CSIDriver instead.
   */
  flexVolume?: IoK8SApiCoreV196;
  /**
   * flocker represents a Flocker volume attached to a kubelet's host machine. This depends on the Flocker control service being running. Deprecated: Flocker is deprecated and the in-tree flocker type is no longer supported.
   */
  flocker?: IoK8SApiCoreV197;
  /**
   * gcePersistentDisk represents a GCE Disk resource that is attached to a kubelet's host machine and then exposed to the pod. Deprecated: GCEPersistentDisk is deprecated. All operations for the in-tree gcePersistentDisk type are redirected to the pd.csi.storage.gke.io CSI driver. More info: https://kubernetes.io/docs/concepts/storage/volumes#gcepersistentdisk
   */
  gcePersistentDisk?: IoK8SApiCoreV198;
  /**
   * gitRepo represents a git repository at a particular revision. Deprecated: GitRepo is deprecated. To provision a container with a git repo, mount an EmptyDir into an InitContainer that clones the repo using git, then mount the EmptyDir into the Pod's container.
   */
  gitRepo?: IoK8SApiCoreV199;
  /**
   * glusterfs represents a Glusterfs mount on the host that shares a pod's lifetime. Deprecated: Glusterfs is deprecated and the in-tree glusterfs type is no longer supported. More info: https://examples.k8s.io/volumes/glusterfs/README.md
   */
  glusterfs?: IoK8SApiCoreV1101;
  /**
   * hostPath represents a pre-existing file or directory on the host machine that is directly exposed to the container. This is generally used for system agents or other privileged things that are allowed to see the host machine. Most containers will NOT need this. More info: https://kubernetes.io/docs/concepts/storage/volumes#hostpath
   */
  hostPath?: IoK8SApiCoreV1104;
  /**
   * image represents an OCI object (a container image or artifact) pulled and mounted on the kubelet's host machine. The volume is resolved at pod startup depending on which PullPolicy value is provided:
   *
   * - Always: the kubelet always attempts to pull the reference. Container creation will fail If the pull fails. - Never: the kubelet never pulls the reference and only uses a local image or artifact. Container creation will fail if the reference isn't present. - IfNotPresent: the kubelet pulls if the reference isn't already present on disk. Container creation will fail if the reference isn't present and the pull fails.
   *
   * The volume gets re-resolved if the pod gets deleted and recreated, which means that new remote content will become available on pod recreation. A failure to resolve or pull the image during pod startup will block containers from starting and may add significant latency. Failures will be retried using normal volume backoff and will be reported on the pod reason and message. The types of objects that may be mounted by this volume are defined by the container runtime implementation on a host machine and at minimum must include all valid types supported by the container image field. The OCI object gets mounted in a single directory (spec.containers[*].volumeMounts.mountPath) by merging the manifest layers in the same way as for container images. The volume will be mounted read-only (ro) and non-executable files (noexec). Sub path mounts for containers are not supported (spec.containers[*].volumeMounts.subpath). The field spec.securityContext.fsGroupChangePolicy has no effect on this volume type.
   */
  image?: IoK8SApiCoreV1107;
  /**
   * iscsi represents an ISCSI Disk resource that is attached to a kubelet's host machine and then exposed to the pod. More info: https://examples.k8s.io/volumes/iscsi/README.md
   */
  iscsi?: IoK8SApiCoreV1106;
  /**
   * name of the volume. Must be a DNS_LABEL and unique within the pod. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name: string;
  /**
   * nfs represents an NFS mount on the host that shares a pod's lifetime More info: https://kubernetes.io/docs/concepts/storage/volumes#nfs
   */
  nfs?: IoK8SApiCoreV1116;
  /**
   * persistentVolumeClaimVolumeSource represents a reference to a PersistentVolumeClaim in the same namespace. More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#persistentvolumeclaims
   */
  persistentVolumeClaim?: IoK8SApiCoreV1148;
  /**
   * photonPersistentDisk represents a PhotonController persistent disk attached and mounted on kubelets host machine. Deprecated: PhotonPersistentDisk is deprecated and the in-tree photonPersistentDisk type is no longer supported.
   */
  photonPersistentDisk?: IoK8SApiCoreV1137;
  /**
   * portworxVolume represents a portworx volume attached and mounted on kubelets host machine. Deprecated: PortworxVolume is deprecated. All operations for the in-tree portworxVolume type are redirected to the pxd.portworx.com CSI driver when the CSIMigrationPortworx feature-gate is on.
   */
  portworxVolume?: IoK8SApiCoreV1138;
  /**
   * projected items for all in one resources secrets, configmaps, and downward API
   */
  projected?: IoK8SApiCoreV1162;
  /**
   * quobyte represents a Quobyte mount on the host that shares a pod's lifetime. Deprecated: Quobyte is deprecated and the in-tree quobyte type is no longer supported.
   */
  quobyte?: IoK8SApiCoreV1139;
  /**
   * rbd represents a Rados Block Device mount on the host that shares a pod's lifetime. Deprecated: RBD is deprecated and the in-tree rbd type is no longer supported. More info: https://examples.k8s.io/volumes/rbd/README.md
   */
  rbd?: IoK8SApiCoreV1166;
  /**
   * scaleIO represents a ScaleIO persistent volume attached and mounted on Kubernetes nodes. Deprecated: ScaleIO is deprecated and the in-tree scaleIO type is no longer supported.
   */
  scaleIO?: IoK8SApiCoreV1167;
  /**
   * secret represents a secret that should populate this volume. More info: https://kubernetes.io/docs/concepts/storage/volumes#secret
   */
  secret?: IoK8SApiCoreV1168;
  /**
   * storageOS represents a StorageOS volume attached and mounted on Kubernetes nodes. Deprecated: StorageOS is deprecated and the in-tree storageos type is no longer supported.
   */
  storageos?: IoK8SApiCoreV1169;
  /**
   * vsphereVolume represents a vSphere volume attached and mounted on kubelets host machine. Deprecated: VsphereVolume is deprecated. All operations for the in-tree vsphereVolume type are redirected to the csi.vsphere.vmware.com CSI driver.
   */
  vsphereVolume?: IoK8SApiCoreV1143;
  [k: string]: unknown;
}
/**
 * Represents a projected volume source
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ProjectedVolumeSource".
 */
export interface IoK8SApiCoreV1162 {
  /**
   * defaultMode are the mode bits used to set permissions on created files by default. Must be an octal value between 0000 and 0777 or a decimal value between 0 and 511. YAML accepts both octal and decimal values, JSON requires decimal values for mode bits. Directories within the path are not affected by this setting. This might be in conflict with other options that affect the file mode, like fsGroup, and the result can be other mode bits set.
   */
  defaultMode?: number;
  /**
   * sources is the list of volume projections. Each entry in this list handles one source.
   */
  sources?: IoK8SApiCoreV1163[];
  [k: string]: unknown;
}
/**
 * Projection that may be projected along with other supported volume types. Exactly one of these fields must be set.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.VolumeProjection".
 */
export interface IoK8SApiCoreV1163 {
  /**
   * ClusterTrustBundle allows a pod to access the `.spec.trustBundle` field of ClusterTrustBundle objects in an auto-updating file.
   *
   * Alpha, gated by the ClusterTrustBundleProjection feature gate.
   *
   * ClusterTrustBundle objects can either be selected by name, or by the combination of signer name and a label selector.
   *
   * Kubelet performs aggressive normalization of the PEM contents written into the pod filesystem.  Esoteric PEM features such as inter-block comments and block headers are stripped.  Certificates are deduplicated. The ordering of certificates within the file is arbitrary, and Kubelet may change the order over time.
   */
  clusterTrustBundle?: IoK8SApiCoreV127;
  /**
   * configMap information about the configMap data to project
   */
  configMap?: IoK8SApiCoreV134;
  /**
   * downwardAPI information about the downwardAPI data to project
   */
  downwardAPI?: IoK8SApiCoreV176;
  /**
   * secret information about the secret data to project
   */
  secret?: IoK8SApiCoreV1164;
  /**
   * serviceAccountToken is information about the serviceAccountToken data to project
   */
  serviceAccountToken?: IoK8SApiCoreV1165;
  [k: string]: unknown;
}
/**
 * Adapts a secret into a projected volume.
 *
 * The contents of the target Secret's Data field will be presented in a projected volume as files using the keys in the Data field as the file names. Note that this is identical to a secret volume source without the default mode.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SecretProjection".
 */
export interface IoK8SApiCoreV1164 {
  /**
   * items if unspecified, each key-value pair in the Data field of the referenced Secret will be projected into the volume as a file whose name is the key and content is the value. If specified, the listed keys will be projected into the specified paths, and unlisted keys will not be present. If a key is specified which is not present in the Secret, the volume setup will error unless it is marked optional. Paths must be relative and may not contain the '..' path or start with '..'.
   */
  items?: IoK8SApiCoreV135[];
  /**
   * Name of the referent. This field is effectively required, but due to backwards compatibility is allowed to be empty. Instances of this type with an empty value here are almost certainly wrong. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
   */
  name?: string;
  /**
   * optional field specify whether the Secret or its key must be defined
   */
  optional?: boolean;
  [k: string]: unknown;
}
/**
 * ServiceAccountTokenProjection represents a projected service account token volume. This projection can be used to insert a service account token into the pods runtime filesystem for use against APIs (Kubernetes API Server or otherwise).
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ServiceAccountTokenProjection".
 */
export interface IoK8SApiCoreV1165 {
  /**
   * audience is the intended audience of the token. A recipient of a token must identify itself with an identifier specified in the audience of the token, and otherwise should reject the token. The audience defaults to the identifier of the apiserver.
   */
  audience?: string;
  /**
   * expirationSeconds is the requested duration of validity of the service account token. As the token approaches expiration, the kubelet volume plugin will proactively rotate the service account token. The kubelet will start trying to rotate the token if the token is older than 80 percent of its time to live or if the token is older than 24 hours.Defaults to 1 hour and must be at least 10 minutes.
   */
  expirationSeconds?: number;
  /**
   * path is the path relative to the mount point of the file to project the token into.
   */
  path: string;
  [k: string]: unknown;
}
/**
 * Represents a Rados Block Device mount that lasts the lifetime of a pod. RBD volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.RBDVolumeSource".
 */
export interface IoK8SApiCoreV1166 {
  /**
   * fsType is the filesystem type of the volume that you want to mount. Tip: Ensure that the filesystem type is supported by the host operating system. Examples: "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified. More info: https://kubernetes.io/docs/concepts/storage/volumes#rbd
   */
  fsType?: string;
  /**
   * image is the rados image name. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  image: string;
  /**
   * keyring is the path to key ring for RBDUser. Default is /etc/ceph/keyring. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  keyring?: string;
  /**
   * monitors is a collection of Ceph monitors. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  monitors: string[];
  /**
   * pool is the rados pool name. Default is rbd. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  pool?: string;
  /**
   * readOnly here will force the ReadOnly setting in VolumeMounts. Defaults to false. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  readOnly?: boolean;
  /**
   * secretRef is name of the authentication secret for RBDUser. If provided overrides keyring. Default is nil. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  secretRef?: IoK8SApiCoreV120;
  /**
   * user is the rados user name. Default is admin. More info: https://examples.k8s.io/volumes/rbd/README.md#how-to-use-it
   */
  user?: string;
  [k: string]: unknown;
}
/**
 * ScaleIOVolumeSource represents a persistent ScaleIO volume
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ScaleIOVolumeSource".
 */
export interface IoK8SApiCoreV1167 {
  /**
   * fsType is the filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". Default is "xfs".
   */
  fsType?: string;
  /**
   * gateway is the host address of the ScaleIO API Gateway.
   */
  gateway: string;
  /**
   * protectionDomain is the name of the ScaleIO Protection Domain for the configured storage.
   */
  protectionDomain?: string;
  /**
   * readOnly Defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * secretRef references to the secret for ScaleIO user and other sensitive information. If this is not provided, Login operation will fail.
   */
  secretRef: IoK8SApiCoreV120;
  /**
   * sslEnabled Flag enable/disable SSL communication with Gateway, default false
   */
  sslEnabled?: boolean;
  /**
   * storageMode indicates whether the storage for a volume should be ThickProvisioned or ThinProvisioned. Default is ThinProvisioned.
   */
  storageMode?: string;
  /**
   * storagePool is the ScaleIO Storage Pool associated with the protection domain.
   */
  storagePool?: string;
  /**
   * system is the name of the storage system as configured in ScaleIO.
   */
  system: string;
  /**
   * volumeName is the name of a volume already created in the ScaleIO system that is associated with this volume source.
   */
  volumeName?: string;
  [k: string]: unknown;
}
/**
 * Adapts a Secret into a volume.
 *
 * The contents of the target Secret's Data field will be presented in a volume as files using the keys in the Data field as the file names. Secret volumes support ownership management and SELinux relabeling.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SecretVolumeSource".
 */
export interface IoK8SApiCoreV1168 {
  /**
   * defaultMode is Optional: mode bits used to set permissions on created files by default. Must be an octal value between 0000 and 0777 or a decimal value between 0 and 511. YAML accepts both octal and decimal values, JSON requires decimal values for mode bits. Defaults to 0644. Directories within the path are not affected by this setting. This might be in conflict with other options that affect the file mode, like fsGroup, and the result can be other mode bits set.
   */
  defaultMode?: number;
  /**
   * items If unspecified, each key-value pair in the Data field of the referenced Secret will be projected into the volume as a file whose name is the key and content is the value. If specified, the listed keys will be projected into the specified paths, and unlisted keys will not be present. If a key is specified which is not present in the Secret, the volume setup will error unless it is marked optional. Paths must be relative and may not contain the '..' path or start with '..'.
   */
  items?: IoK8SApiCoreV135[];
  /**
   * optional field specify whether the Secret or its keys must be defined
   */
  optional?: boolean;
  /**
   * secretName is the name of the secret in the pod's namespace to use. More info: https://kubernetes.io/docs/concepts/storage/volumes#secret
   */
  secretName?: string;
  [k: string]: unknown;
}
/**
 * Represents a StorageOS persistent volume resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.StorageOSVolumeSource".
 */
export interface IoK8SApiCoreV1169 {
  /**
   * fsType is the filesystem type to mount. Must be a filesystem type supported by the host operating system. Ex. "ext4", "xfs", "ntfs". Implicitly inferred to be "ext4" if unspecified.
   */
  fsType?: string;
  /**
   * readOnly defaults to false (read/write). ReadOnly here will force the ReadOnly setting in VolumeMounts.
   */
  readOnly?: boolean;
  /**
   * secretRef specifies the secret to use for obtaining the StorageOS API credentials.  If not specified, default values will be attempted.
   */
  secretRef?: IoK8SApiCoreV120;
  /**
   * volumeName is the human-readable name of the StorageOS volume.  Volume names are only unique within a namespace.
   */
  volumeName?: string;
  /**
   * volumeNamespace specifies the scope of the volume within StorageOS.  If no namespace is specified then the Pod's namespace will be used.  This allows the Kubernetes name scoping to be mirrored within StorageOS for tighter integration. Set VolumeName to any name to override the default behaviour. Set to "default" if you are not using namespaces within StorageOS. Namespaces that do not pre-exist within StorageOS will be created.
   */
  volumeNamespace?: string;
  [k: string]: unknown;
}
/**
 * PodStatus represents information about the status of a pod. Status may trail the actual state of a system, especially if the node that hosts the pod cannot contact the control plane.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodStatus".
 */
export interface IoK8SApiCoreV1170 {
  /**
   * Current service state of pod. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#pod-conditions
   */
  conditions?: IoK8SApiCoreV1171[];
  /**
   * Statuses of containers in this pod. Each container in the pod should have at most one status in this list, and all statuses should be for containers in the pod. However this is not enforced. If a status for a non-existent container is present in the list, or the list has duplicate names, the behavior of various Kubernetes components is not defined and those statuses might be ignored. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#pod-and-container-status
   */
  containerStatuses?: IoK8SApiCoreV169[];
  /**
   * Statuses for any ephemeral containers that have run in this pod. Each ephemeral container in the pod should have at most one status in this list, and all statuses should be for containers in the pod. However this is not enforced. If a status for a non-existent container is present in the list, or the list has duplicate names, the behavior of various Kubernetes components is not defined and those statuses might be ignored. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#pod-and-container-status
   */
  ephemeralContainerStatuses?: IoK8SApiCoreV169[];
  /**
   * hostIP holds the IP address of the host to which the pod is assigned. Empty if the pod has not started yet. A pod can be assigned to a node that has a problem in kubelet which in turns mean that HostIP will not be updated even if there is a node is assigned to pod
   */
  hostIP?: string;
  /**
   * hostIPs holds the IP addresses allocated to the host. If this field is specified, the first entry must match the hostIP field. This list is empty if the pod has not started yet. A pod can be assigned to a node that has a problem in kubelet which in turns means that HostIPs will not be updated even if there is a node is assigned to this pod.
   */
  hostIPs?: IoK8SApiCoreV1103[];
  /**
   * Statuses of init containers in this pod. The most recent successful non-restartable init container will have ready = true, the most recently started container will have startTime set. Each init container in the pod should have at most one status in this list, and all statuses should be for containers in the pod. However this is not enforced. If a status for a non-existent container is present in the list, or the list has duplicate names, the behavior of various Kubernetes components is not defined and those statuses might be ignored. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-and-container-status
   */
  initContainerStatuses?: IoK8SApiCoreV169[];
  /**
   * A human readable message indicating details about why the pod is in this condition.
   */
  message?: string;
  /**
   * nominatedNodeName is set only when this pod preempts other pods on the node, but it cannot be scheduled right away as preemption victims receive their graceful termination periods. This field does not guarantee that the pod will be scheduled on this node. Scheduler may decide to place the pod elsewhere if other nodes become available sooner. Scheduler may also decide to give the resources on this node to a higher priority pod that is created after preemption. As a result, this field may be different than PodSpec.nodeName when the pod is scheduled.
   */
  nominatedNodeName?: string;
  /**
   * The phase of a Pod is a simple, high-level summary of where the Pod is in its lifecycle. The conditions array, the reason and message fields, and the individual container status arrays contain more detail about the pod's status. There are five possible phase values:
   *
   * Pending: The pod has been accepted by the Kubernetes system, but one or more of the container images has not been created. This includes time before being scheduled as well as time spent downloading images over the network, which could take a while. Running: The pod has been bound to a node, and all of the containers have been created. At least one container is still running, or is in the process of starting or restarting. Succeeded: All containers in the pod have terminated in success, and will not be restarted. Failed: All containers in the pod have terminated, and at least one container has terminated in failure. The container either exited with non-zero status or was terminated by the system. Unknown: For some reason the state of the pod could not be obtained, typically due to an error in communicating with the host of the pod.
   *
   * More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#pod-phase
   *
   * Possible enum values:
   *  - `"Failed"` means that all containers in the pod have terminated, and at least one container has terminated in a failure (exited with a non-zero exit code or was stopped by the system).
   *  - `"Pending"` means the pod has been accepted by the system, but one or more of the containers has not been started. This includes time before being bound to a node, as well as time spent pulling images onto the host.
   *  - `"Running"` means the pod has been bound to a node and all of the containers have been started. At least one container is still running or is in the process of being restarted.
   *  - `"Succeeded"` means that all containers in the pod have voluntarily terminated with a container exit code of 0, and the system is not going to restart any of these containers.
   *  - `"Unknown"` means that for some reason the state of the pod could not be obtained, typically due to an error in communicating with the host of the pod. Deprecated: It isn't being set since 2015 (74da3b14b0c0f658b3bb8d2def5094686d0e9095)
   */
  phase?: "Failed" | "Pending" | "Running" | "Succeeded" | "Unknown";
  /**
   * podIP address allocated to the pod. Routable at least within the cluster. Empty if not yet allocated.
   */
  podIP?: string;
  /**
   * podIPs holds the IP addresses allocated to the pod. If this field is specified, the 0th entry must match the podIP field. Pods may be allocated at most 1 value for each of IPv4 and IPv6. This list is empty if no IPs have been allocated yet.
   */
  podIPs?: IoK8SApiCoreV1172[];
  /**
   * The Quality of Service (QOS) classification assigned to the pod based on resource requirements See PodQOSClass type for available QOS classes More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/#quality-of-service-classes
   *
   * Possible enum values:
   *  - `"BestEffort"` is the BestEffort qos class.
   *  - `"Burstable"` is the Burstable qos class.
   *  - `"Guaranteed"` is the Guaranteed qos class.
   */
  qosClass?: "BestEffort" | "Burstable" | "Guaranteed";
  /**
   * A brief CamelCase message indicating details about why the pod is in this state. e.g. 'Evicted'
   */
  reason?: string;
  /**
   * Status of resources resize desired for pod's containers. It is empty if no resources resize is pending. Any changes to container resources will automatically set this to "Proposed"
   */
  resize?: string;
  /**
   * Status of resource claims.
   */
  resourceClaimStatuses?: IoK8SApiCoreV1173[];
  /**
   * RFC 3339 date and time at which the object was acknowledged by the Kubelet. This is before the Kubelet pulled the container image(s) for the pod.
   */
  startTime?: IoK8SApimachineryPkgApisMetaV1Time;
  [k: string]: unknown;
}
/**
 * PodCondition contains details for the current condition of this pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodCondition".
 */
export interface IoK8SApiCoreV1171 {
  /**
   * Last time we probed the condition.
   */
  lastProbeTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Last time the condition transitioned from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Human-readable message indicating details about last transition.
   */
  message?: string;
  /**
   * Unique, one-word, CamelCase reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status is the status of the condition. Can be True, False, Unknown. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#pod-conditions
   */
  status: string;
  /**
   * Type is the type of the condition. More info: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle#pod-conditions
   */
  type: string;
  [k: string]: unknown;
}
/**
 * PodIP represents a single IP address allocated to the pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodIP".
 */
export interface IoK8SApiCoreV1172 {
  /**
   * IP is the IP address assigned to the pod
   */
  ip: string;
  [k: string]: unknown;
}
/**
 * PodResourceClaimStatus is stored in the PodStatus for each PodResourceClaim which references a ResourceClaimTemplate. It stores the generated name for the corresponding ResourceClaim.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodResourceClaimStatus".
 */
export interface IoK8SApiCoreV1173 {
  /**
   * Name uniquely identifies this resource claim inside the pod. This must match the name of an entry in pod.spec.resourceClaims, which implies that the string must be a DNS_LABEL.
   */
  name: string;
  /**
   * ResourceClaimName is the name of the ResourceClaim that was generated for the Pod in the namespace of the Pod. If this is unset, then generating a ResourceClaim was not necessary. The pod.spec.resourceClaims entry can be ignored in this case.
   */
  resourceClaimName?: string;
  [k: string]: unknown;
}
/**
 * PodList is a list of Pods.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodList".
 */
export interface IoK8SApiCoreV1PodList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of pods. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md
   */
  items: IoK8SApiCoreV1149[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * PodTemplate describes a template for creating copies of a predefined pod.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodTemplate".
 */
export interface IoK8SApiCoreV1174 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Template defines the pods that will be created from this pod template. https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  template?: IoK8SApiCoreV1175;
  [k: string]: unknown;
}
/**
 * PodTemplateSpec describes the data a pod should have when created from a template
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodTemplateSpec".
 */
export interface IoK8SApiCoreV1175 {
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Specification of the desired behavior of the pod. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiCoreV1150;
  [k: string]: unknown;
}
/**
 * PodTemplateList is a list of PodTemplates.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.PodTemplateList".
 */
export interface IoK8SApiCoreV1PodTemplateList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of pod templates
   */
  items: IoK8SApiCoreV1174[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * ReplicationController represents the configuration of a replication controller.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ReplicationController".
 */
export interface IoK8SApiCoreV1176 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * If the Labels of a ReplicationController are empty, they are defaulted to be the same as the Pod(s) that the replication controller manages. Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec defines the specification of the desired behavior of the replication controller. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiCoreV1177;
  /**
   * Status is the most recently observed status of the replication controller. This data may be out of date by some window of time. Populated by the system. Read-only. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiCoreV1178;
  [k: string]: unknown;
}
/**
 * ReplicationControllerSpec is the specification of a replication controller.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ReplicationControllerSpec".
 */
export interface IoK8SApiCoreV1177 {
  /**
   * Minimum number of seconds for which a newly created pod should be ready without any of its container crashing, for it to be considered available. Defaults to 0 (pod will be considered available as soon as it is ready)
   */
  minReadySeconds?: number;
  /**
   * Replicas is the number of desired replicas. This is a pointer to distinguish between explicit zero and unspecified. Defaults to 1. More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller#what-is-a-replicationcontroller
   */
  replicas?: number;
  /**
   * Selector is a label query over pods that should match the Replicas count. If Selector is empty, it is defaulted to the labels present on the Pod template. Label keys and values that must match in order to be controlled by this replication controller, if empty defaulted to labels on Pod template. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#label-selectors
   */
  selector?: {
    [k: string]: string;
  };
  /**
   * Template is the object that describes the pod that will be created if insufficient replicas are detected. This takes precedence over a TemplateRef. The only allowed template.spec.restartPolicy value is "Always". More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller#pod-template
   */
  template?: IoK8SApiCoreV1175;
  [k: string]: unknown;
}
/**
 * ReplicationControllerStatus represents the current status of a replication controller.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ReplicationControllerStatus".
 */
export interface IoK8SApiCoreV1178 {
  /**
   * The number of available replicas (ready for at least minReadySeconds) for this replication controller.
   */
  availableReplicas?: number;
  /**
   * Represents the latest available observations of a replication controller's current state.
   */
  conditions?: IoK8SApiCoreV1179[];
  /**
   * The number of pods that have labels matching the labels of the pod template of the replication controller.
   */
  fullyLabeledReplicas?: number;
  /**
   * ObservedGeneration reflects the generation of the most recently observed replication controller.
   */
  observedGeneration?: number;
  /**
   * The number of ready replicas for this replication controller.
   */
  readyReplicas?: number;
  /**
   * Replicas is the most recently observed number of replicas. More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller#what-is-a-replicationcontroller
   */
  replicas: number;
  [k: string]: unknown;
}
/**
 * ReplicationControllerCondition describes the state of a replication controller at a certain point.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ReplicationControllerCondition".
 */
export interface IoK8SApiCoreV1179 {
  /**
   * The last time the condition transitioned from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * A human readable message indicating details about the transition.
   */
  message?: string;
  /**
   * The reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * Type of replication controller condition.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * ReplicationControllerList is a collection of replication controllers.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ReplicationControllerList".
 */
export interface IoK8SApiCoreV1ReplicationControllerList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of replication controllers. More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller
   */
  items: IoK8SApiCoreV1176[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * ResourceQuota sets aggregate quota restrictions enforced per namespace
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceQuota".
 */
export interface IoK8SApiCoreV1180 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec defines the desired quota. https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiCoreV1181;
  /**
   * Status defines the actual enforced quota and its current usage. https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiCoreV1184;
  [k: string]: unknown;
}
/**
 * ResourceQuotaSpec defines the desired hard limits to enforce for Quota.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceQuotaSpec".
 */
export interface IoK8SApiCoreV1181 {
  /**
   * hard is the set of desired hard limits for each named resource. More info: https://kubernetes.io/docs/concepts/policy/resource-quotas/
   */
  hard?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * scopeSelector is also a collection of filters like scopes that must match each object tracked by a quota but expressed using ScopeSelectorOperator in combination with possible values. For a resource to match, both scopes AND scopeSelector (if specified in spec), must be matched.
   */
  scopeSelector?: IoK8SApiCoreV1182;
  /**
   * A collection of filters that must match each object tracked by a quota. If not specified, the quota matches all objects.
   */
  scopes?: (
    | "BestEffort"
    | "CrossNamespacePodAffinity"
    | "NotBestEffort"
    | "NotTerminating"
    | "PriorityClass"
    | "Terminating"
  )[];
  [k: string]: unknown;
}
/**
 * A scope selector represents the AND of the selectors represented by the scoped-resource selector requirements.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ScopeSelector".
 */
export interface IoK8SApiCoreV1182 {
  /**
   * A list of scope selector requirements by scope of the resources.
   */
  matchExpressions?: IoK8SApiCoreV1183[];
  [k: string]: unknown;
}
/**
 * A scoped-resource selector requirement is a selector that contains values, a scope name, and an operator that relates the scope name and values.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ScopedResourceSelectorRequirement".
 */
export interface IoK8SApiCoreV1183 {
  /**
   * Represents a scope's relationship to a set of values. Valid operators are In, NotIn, Exists, DoesNotExist.
   *
   * Possible enum values:
   *  - `"DoesNotExist"`
   *  - `"Exists"`
   *  - `"In"`
   *  - `"NotIn"`
   */
  operator: "DoesNotExist" | "Exists" | "In" | "NotIn";
  /**
   * The name of the scope that the selector applies to.
   *
   * Possible enum values:
   *  - `"BestEffort"` Match all pod objects that have best effort quality of service
   *  - `"CrossNamespacePodAffinity"` Match all pod objects that have cross-namespace pod (anti)affinity mentioned.
   *  - `"NotBestEffort"` Match all pod objects that do not have best effort quality of service
   *  - `"NotTerminating"` Match all pod objects where spec.activeDeadlineSeconds is nil
   *  - `"PriorityClass"` Match all pod objects that have priority class mentioned
   *  - `"Terminating"` Match all pod objects where spec.activeDeadlineSeconds >=0
   */
  scopeName:
    | "BestEffort"
    | "CrossNamespacePodAffinity"
    | "NotBestEffort"
    | "NotTerminating"
    | "PriorityClass"
    | "Terminating";
  /**
   * An array of string values. If the operator is In or NotIn, the values array must be non-empty. If the operator is Exists or DoesNotExist, the values array must be empty. This array is replaced during a strategic merge patch.
   */
  values?: string[];
  [k: string]: unknown;
}
/**
 * ResourceQuotaStatus defines the enforced hard limits and observed use.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceQuotaStatus".
 */
export interface IoK8SApiCoreV1184 {
  /**
   * Hard is the set of enforced hard limits for each named resource. More info: https://kubernetes.io/docs/concepts/policy/resource-quotas/
   */
  hard?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  /**
   * Used is the current observed total usage of the resource in the namespace.
   */
  used?: {
    [k: string]: IoK8SApimachineryPkgApiResourceQuantity;
  };
  [k: string]: unknown;
}
/**
 * ResourceQuotaList is a list of ResourceQuota items.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ResourceQuotaList".
 */
export interface IoK8SApiCoreV1ResourceQuotaList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Items is a list of ResourceQuota objects. More info: https://kubernetes.io/docs/concepts/policy/resource-quotas/
   */
  items: IoK8SApiCoreV1180[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * Secret holds secret data of a certain type. The total bytes of the values in the Data field must be less than MaxSecretSize bytes.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Secret".
 */
export interface IoK8SApiCoreV1185 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Data contains the secret data. Each key must consist of alphanumeric characters, '-', '_' or '.'. The serialized form of the secret data is a base64 encoded string, representing the arbitrary (possibly non-string) data value here. Described in https://tools.ietf.org/html/rfc4648#section-4
   */
  data?: {
    [k: string]: string;
  };
  /**
   * Immutable, if set to true, ensures that data stored in the Secret cannot be updated (only object metadata can be modified). If not set to true, the field can be modified at any time. Defaulted to nil.
   */
  immutable?: boolean;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * stringData allows specifying non-binary secret data in string form. It is provided as a write-only input field for convenience. All keys and values are merged into the data field on write, overwriting any existing values. The stringData field is never output when reading from the API.
   */
  stringData?: {
    [k: string]: string;
  };
  /**
   * Used to facilitate programmatic handling of secret data. More info: https://kubernetes.io/docs/concepts/configuration/secret/#secret-types
   */
  type?: string;
  [k: string]: unknown;
}
/**
 * SecretList is a list of Secret.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SecretList".
 */
export interface IoK8SApiCoreV1SecretList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Items is a list of secret objects. More info: https://kubernetes.io/docs/concepts/configuration/secret
   */
  items: IoK8SApiCoreV1185[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * Service is a named abstraction of software service (for example, mysql) consisting of local port (for example 3306) that the proxy listens on, and the selector that determines which pods will answer requests sent through the proxy.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.Service".
 */
export interface IoK8SApiCoreV1186 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec defines the behavior of a service. https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiCoreV1187;
  /**
   * Most recently observed status of the service. Populated by the system. Read-only. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiCoreV1190;
  [k: string]: unknown;
}
/**
 * ServiceSpec describes the attributes that a user creates on a service.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ServiceSpec".
 */
export interface IoK8SApiCoreV1187 {
  /**
   * allocateLoadBalancerNodePorts defines if NodePorts will be automatically allocated for services with type LoadBalancer.  Default is "true". It may be set to "false" if the cluster load-balancer does not rely on NodePorts.  If the caller requests specific NodePorts (by specifying a value), those requests will be respected, regardless of this field. This field may only be set for services with type LoadBalancer and will be cleared if the type is changed to any other type.
   */
  allocateLoadBalancerNodePorts?: boolean;
  /**
   * clusterIP is the IP address of the service and is usually assigned randomly. If an address is specified manually, is in-range (as per system configuration), and is not in use, it will be allocated to the service; otherwise creation of the service will fail. This field may not be changed through updates unless the type field is also being changed to ExternalName (which requires this field to be blank) or the type field is being changed from ExternalName (in which case this field may optionally be specified, as describe above).  Valid values are "None", empty string (""), or a valid IP address. Setting this to "None" makes a "headless service" (no virtual IP), which is useful when direct endpoint connections are preferred and proxying is not required.  Only applies to types ClusterIP, NodePort, and LoadBalancer. If this field is specified when creating a Service of type ExternalName, creation will fail. This field will be wiped when updating a Service to type ExternalName. More info: https://kubernetes.io/docs/concepts/services-networking/service/#virtual-ips-and-service-proxies
   */
  clusterIP?: string;
  /**
   * ClusterIPs is a list of IP addresses assigned to this service, and are usually assigned randomly.  If an address is specified manually, is in-range (as per system configuration), and is not in use, it will be allocated to the service; otherwise creation of the service will fail. This field may not be changed through updates unless the type field is also being changed to ExternalName (which requires this field to be empty) or the type field is being changed from ExternalName (in which case this field may optionally be specified, as describe above).  Valid values are "None", empty string (""), or a valid IP address.  Setting this to "None" makes a "headless service" (no virtual IP), which is useful when direct endpoint connections are preferred and proxying is not required.  Only applies to types ClusterIP, NodePort, and LoadBalancer. If this field is specified when creating a Service of type ExternalName, creation will fail. This field will be wiped when updating a Service to type ExternalName.  If this field is not specified, it will be initialized from the clusterIP field.  If this field is specified, clients must ensure that clusterIPs[0] and clusterIP have the same value.
   *
   * This field may hold a maximum of two entries (dual-stack IPs, in either order). These IPs must correspond to the values of the ipFamilies field. Both clusterIPs and ipFamilies are governed by the ipFamilyPolicy field. More info: https://kubernetes.io/docs/concepts/services-networking/service/#virtual-ips-and-service-proxies
   */
  clusterIPs?: string[];
  /**
   * externalIPs is a list of IP addresses for which nodes in the cluster will also accept traffic for this service.  These IPs are not managed by Kubernetes.  The user is responsible for ensuring that traffic arrives at a node with this IP.  A common example is external load-balancers that are not part of the Kubernetes system.
   */
  externalIPs?: string[];
  /**
   * externalName is the external reference that discovery mechanisms will return as an alias for this service (e.g. a DNS CNAME record). No proxying will be involved.  Must be a lowercase RFC-1123 hostname (https://tools.ietf.org/html/rfc1123) and requires `type` to be "ExternalName".
   */
  externalName?: string;
  /**
   * externalTrafficPolicy describes how nodes distribute service traffic they receive on one of the Service's "externally-facing" addresses (NodePorts, ExternalIPs, and LoadBalancer IPs). If set to "Local", the proxy will configure the service in a way that assumes that external load balancers will take care of balancing the service traffic between nodes, and so each node will deliver traffic only to the node-local endpoints of the service, without masquerading the client source IP. (Traffic mistakenly sent to a node with no endpoints will be dropped.) The default value, "Cluster", uses the standard behavior of routing to all endpoints evenly (possibly modified by topology and other features). Note that traffic sent to an External IP or LoadBalancer IP from within the cluster will always get "Cluster" semantics, but clients sending to a NodePort from within the cluster may need to take traffic policy into account when picking a node.
   *
   * Possible enum values:
   *  - `"Cluster"` routes traffic to all endpoints.
   *  - `"Local"` preserves the source IP of the traffic by routing only to endpoints on the same node as the traffic was received on (dropping the traffic if there are no local endpoints).
   */
  externalTrafficPolicy?: "Cluster" | "Local";
  /**
   * healthCheckNodePort specifies the healthcheck nodePort for the service. This only applies when type is set to LoadBalancer and externalTrafficPolicy is set to Local. If a value is specified, is in-range, and is not in use, it will be used.  If not specified, a value will be automatically allocated.  External systems (e.g. load-balancers) can use this port to determine if a given node holds endpoints for this service or not.  If this field is specified when creating a Service which does not need it, creation will fail. This field will be wiped when updating a Service to no longer need it (e.g. changing type). This field cannot be updated once set.
   */
  healthCheckNodePort?: number;
  /**
   * InternalTrafficPolicy describes how nodes distribute service traffic they receive on the ClusterIP. If set to "Local", the proxy will assume that pods only want to talk to endpoints of the service on the same node as the pod, dropping the traffic if there are no local endpoints. The default value, "Cluster", uses the standard behavior of routing to all endpoints evenly (possibly modified by topology and other features).
   *
   * Possible enum values:
   *  - `"Cluster"` routes traffic to all endpoints.
   *  - `"Local"` routes traffic only to endpoints on the same node as the client pod (dropping the traffic if there are no local endpoints).
   */
  internalTrafficPolicy?: "Cluster" | "Local";
  /**
   * IPFamilies is a list of IP families (e.g. IPv4, IPv6) assigned to this service. This field is usually assigned automatically based on cluster configuration and the ipFamilyPolicy field. If this field is specified manually, the requested family is available in the cluster, and ipFamilyPolicy allows it, it will be used; otherwise creation of the service will fail. This field is conditionally mutable: it allows for adding or removing a secondary IP family, but it does not allow changing the primary IP family of the Service. Valid values are "IPv4" and "IPv6".  This field only applies to Services of types ClusterIP, NodePort, and LoadBalancer, and does apply to "headless" services. This field will be wiped when updating a Service to type ExternalName.
   *
   * This field may hold a maximum of two entries (dual-stack families, in either order).  These families must correspond to the values of the clusterIPs field, if specified. Both clusterIPs and ipFamilies are governed by the ipFamilyPolicy field.
   */
  ipFamilies?: ("" | "IPv4" | "IPv6")[];
  /**
   * IPFamilyPolicy represents the dual-stack-ness requested or required by this Service. If there is no value provided, then this field will be set to SingleStack. Services can be "SingleStack" (a single IP family), "PreferDualStack" (two IP families on dual-stack configured clusters or a single IP family on single-stack clusters), or "RequireDualStack" (two IP families on dual-stack configured clusters, otherwise fail). The ipFamilies and clusterIPs fields depend on the value of this field. This field will be wiped when updating a service to type ExternalName.
   *
   * Possible enum values:
   *  - `"PreferDualStack"` indicates that this service prefers dual-stack when the cluster is configured for dual-stack. If the cluster is not configured for dual-stack the service will be assigned a single IPFamily. If the IPFamily is not set in service.spec.ipFamilies then the service will be assigned the default IPFamily configured on the cluster
   *  - `"RequireDualStack"` indicates that this service requires dual-stack. Using IPFamilyPolicyRequireDualStack on a single stack cluster will result in validation errors. The IPFamilies (and their order) assigned to this service is based on service.spec.ipFamilies. If service.spec.ipFamilies was not provided then it will be assigned according to how they are configured on the cluster. If service.spec.ipFamilies has only one entry then the alternative IPFamily will be added by apiserver
   *  - `"SingleStack"` indicates that this service is required to have a single IPFamily. The IPFamily assigned is based on the default IPFamily used by the cluster or as identified by service.spec.ipFamilies field
   */
  ipFamilyPolicy?: "PreferDualStack" | "RequireDualStack" | "SingleStack";
  /**
   * loadBalancerClass is the class of the load balancer implementation this Service belongs to. If specified, the value of this field must be a label-style identifier, with an optional prefix, e.g. "internal-vip" or "example.com/internal-vip". Unprefixed names are reserved for end-users. This field can only be set when the Service type is 'LoadBalancer'. If not set, the default load balancer implementation is used, today this is typically done through the cloud provider integration, but should apply for any default implementation. If set, it is assumed that a load balancer implementation is watching for Services with a matching class. Any default load balancer implementation (e.g. cloud providers) should ignore Services that set this field. This field can only be set when creating or updating a Service to type 'LoadBalancer'. Once set, it can not be changed. This field will be wiped when a service is updated to a non 'LoadBalancer' type.
   */
  loadBalancerClass?: string;
  /**
   * Only applies to Service Type: LoadBalancer. This feature depends on whether the underlying cloud-provider supports specifying the loadBalancerIP when a load balancer is created. This field will be ignored if the cloud-provider does not support the feature. Deprecated: This field was under-specified and its meaning varies across implementations. Using it is non-portable and it may not support dual-stack. Users are encouraged to use implementation-specific annotations when available.
   */
  loadBalancerIP?: string;
  /**
   * If specified and supported by the platform, this will restrict traffic through the cloud-provider load-balancer will be restricted to the specified client IPs. This field will be ignored if the cloud-provider does not support the feature." More info: https://kubernetes.io/docs/tasks/access-application-cluster/create-external-load-balancer/
   */
  loadBalancerSourceRanges?: string[];
  /**
   * The list of ports that are exposed by this service. More info: https://kubernetes.io/docs/concepts/services-networking/service/#virtual-ips-and-service-proxies
   */
  ports?: IoK8SApiCoreV1188[];
  /**
   * publishNotReadyAddresses indicates that any agent which deals with endpoints for this Service should disregard any indications of ready/not-ready. The primary use case for setting this field is for a StatefulSet's Headless Service to propagate SRV DNS records for its Pods for the purpose of peer discovery. The Kubernetes controllers that generate Endpoints and EndpointSlice resources for Services interpret this to mean that all endpoints are considered "ready" even if the Pods themselves are not. Agents which consume only Kubernetes generated endpoints through the Endpoints or EndpointSlice resources can safely assume this behavior.
   */
  publishNotReadyAddresses?: boolean;
  /**
   * Route service traffic to pods with label keys and values matching this selector. If empty or not present, the service is assumed to have an external process managing its endpoints, which Kubernetes will not modify. Only applies to types ClusterIP, NodePort, and LoadBalancer. Ignored if type is ExternalName. More info: https://kubernetes.io/docs/concepts/services-networking/service/
   */
  selector?: {
    [k: string]: string;
  };
  /**
   * Supports "ClientIP" and "None". Used to maintain session affinity. Enable client IP based session affinity. Must be ClientIP or None. Defaults to None. More info: https://kubernetes.io/docs/concepts/services-networking/service/#virtual-ips-and-service-proxies
   *
   * Possible enum values:
   *  - `"ClientIP"` is the Client IP based.
   *  - `"None"` - no session affinity.
   */
  sessionAffinity?: "ClientIP" | "None";
  /**
   * sessionAffinityConfig contains the configurations of session affinity.
   */
  sessionAffinityConfig?: IoK8SApiCoreV1189;
  /**
   * TrafficDistribution offers a way to express preferences for how traffic is distributed to Service endpoints. Implementations can use this field as a hint, but are not required to guarantee strict adherence. If the field is not set, the implementation will apply its default routing strategy. If set to "PreferClose", implementations should prioritize endpoints that are topologically close (e.g., same zone). This is a beta field and requires enabling ServiceTrafficDistribution feature.
   */
  trafficDistribution?: string;
  /**
   * type determines how the Service is exposed. Defaults to ClusterIP. Valid options are ExternalName, ClusterIP, NodePort, and LoadBalancer. "ClusterIP" allocates a cluster-internal IP address for load-balancing to endpoints. Endpoints are determined by the selector or if that is not specified, by manual construction of an Endpoints object or EndpointSlice objects. If clusterIP is "None", no virtual IP is allocated and the endpoints are published as a set of endpoints rather than a virtual IP. "NodePort" builds on ClusterIP and allocates a port on every node which routes to the same endpoints as the clusterIP. "LoadBalancer" builds on NodePort and creates an external load-balancer (if supported in the current cloud) which routes to the same endpoints as the clusterIP. "ExternalName" aliases this service to the specified externalName. Several other fields do not apply to ExternalName services. More info: https://kubernetes.io/docs/concepts/services-networking/service/#publishing-services-service-types
   *
   * Possible enum values:
   *  - `"ClusterIP"` means a service will only be accessible inside the cluster, via the cluster IP.
   *  - `"ExternalName"` means a service consists of only a reference to an external name that kubedns or equivalent will return as a CNAME record, with no exposing or proxying of any pods involved.
   *  - `"LoadBalancer"` means a service will be exposed via an external load balancer (if the cloud provider supports it), in addition to 'NodePort' type.
   *  - `"NodePort"` means a service will be exposed on one port of every node, in addition to 'ClusterIP' type.
   */
  type?: "ClusterIP" | "ExternalName" | "LoadBalancer" | "NodePort";
  [k: string]: unknown;
}
/**
 * ServicePort contains information on service's port.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ServicePort".
 */
export interface IoK8SApiCoreV1188 {
  /**
   * The application protocol for this port. This is used as a hint for implementations to offer richer behavior for protocols that they understand. This field follows standard Kubernetes label syntax. Valid values are either:
   *
   * * Un-prefixed protocol names - reserved for IANA standard service names (as per RFC-6335 and https://www.iana.org/assignments/service-names).
   *
   * * Kubernetes-defined prefixed names:
   *   * 'kubernetes.io/h2c' - HTTP/2 prior knowledge over cleartext as described in https://www.rfc-editor.org/rfc/rfc9113.html#name-starting-http-2-with-prior-
   *   * 'kubernetes.io/ws'  - WebSocket over cleartext as described in https://www.rfc-editor.org/rfc/rfc6455
   *   * 'kubernetes.io/wss' - WebSocket over TLS as described in https://www.rfc-editor.org/rfc/rfc6455
   *
   * * Other protocols should use implementation-defined prefixed names such as mycompany.com/my-custom-protocol.
   */
  appProtocol?: string;
  /**
   * The name of this port within the service. This must be a DNS_LABEL. All ports within a ServiceSpec must have unique names. When considering the endpoints for a Service, this must match the 'name' field in the EndpointPort. Optional if only one ServicePort is defined on this service.
   */
  name?: string;
  /**
   * The port on each node on which this service is exposed when type is NodePort or LoadBalancer.  Usually assigned by the system. If a value is specified, in-range, and not in use it will be used, otherwise the operation will fail.  If not specified, a port will be allocated if this Service requires one.  If this field is specified when creating a Service which does not need it, creation will fail. This field will be wiped when updating a Service to no longer need it (e.g. changing type from NodePort to ClusterIP). More info: https://kubernetes.io/docs/concepts/services-networking/service/#type-nodeport
   */
  nodePort?: number;
  /**
   * The port that will be exposed by this service.
   */
  port: number;
  /**
   * The IP protocol for this port. Supports "TCP", "UDP", and "SCTP". Default is TCP.
   *
   * Possible enum values:
   *  - `"SCTP"` is the SCTP protocol.
   *  - `"TCP"` is the TCP protocol.
   *  - `"UDP"` is the UDP protocol.
   */
  protocol?: "SCTP" | "TCP" | "UDP";
  /**
   * Number or name of the port to access on the pods targeted by the service. Number must be in the range 1 to 65535. Name must be an IANA_SVC_NAME. If this is a string, it will be looked up as a named port in the target Pod's container ports. If this is not specified, the value of the 'port' field is used (an identity map). This field is ignored for services with clusterIP=None, and should be omitted or set equal to the 'port' field. More info: https://kubernetes.io/docs/concepts/services-networking/service/#defining-a-service
   */
  targetPort?: IoK8SApimachineryPkgUtilIntstrIntOrString;
  [k: string]: unknown;
}
/**
 * SessionAffinityConfig represents the configurations of session affinity.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.SessionAffinityConfig".
 */
export interface IoK8SApiCoreV1189 {
  /**
   * clientIP contains the configurations of Client IP based session affinity.
   */
  clientIP?: IoK8SApiCoreV126;
  [k: string]: unknown;
}
/**
 * ServiceStatus represents the current status of a service.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ServiceStatus".
 */
export interface IoK8SApiCoreV1190 {
  /**
   * Current service state
   */
  conditions?: IoK8SApimachineryPkgApisMetaV17[];
  /**
   * LoadBalancer contains the current status of the load-balancer, if one is present.
   */
  loadBalancer?: IoK8SApiCoreV1113;
  [k: string]: unknown;
}
/**
 * Condition contains details for one aspect of the current state of this API Resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.Condition".
 */
export interface IoK8SApimachineryPkgApisMetaV17 {
  /**
   * lastTransitionTime is the last time the condition transitioned from one status to another. This should be when the underlying condition changed.  If that is not known, then using the time when the API field changed is acceptable.
   */
  lastTransitionTime: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * message is a human readable message indicating details about the transition. This may be an empty string.
   */
  message: string;
  /**
   * observedGeneration represents the .metadata.generation that the condition was set based upon. For instance, if .metadata.generation is currently 12, but the .status.conditions[x].observedGeneration is 9, the condition is out of date with respect to the current state of the instance.
   */
  observedGeneration?: number;
  /**
   * reason contains a programmatic identifier indicating the reason for the condition's last transition. Producers of specific condition types may define expected values and meanings for this field, and whether the values are considered a guaranteed API. The value should be a CamelCase string. This field may not be empty.
   */
  reason: string;
  /**
   * status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * type of condition in CamelCase or in foo.example.com/CamelCase.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * ServiceAccount binds together: * a name, understood by users, and perhaps by peripheral systems, for an identity * a principal that can be authenticated and authorized * a set of secrets
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ServiceAccount".
 */
export interface IoK8SApiCoreV1191 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * AutomountServiceAccountToken indicates whether pods running as this service account should have an API token automatically mounted. Can be overridden at the pod level.
   */
  automountServiceAccountToken?: boolean;
  /**
   * ImagePullSecrets is a list of references to secrets in the same namespace to use for pulling any images in pods that reference this ServiceAccount. ImagePullSecrets are distinct from Secrets because Secrets can be mounted in the pod, but ImagePullSecrets are only accessed by the kubelet. More info: https://kubernetes.io/docs/concepts/containers/images/#specifying-imagepullsecrets-on-a-pod
   */
  imagePullSecrets?: IoK8SApiCoreV120[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Secrets is a list of the secrets in the same namespace that pods running using this ServiceAccount are allowed to use. Pods are only limited to this list if this service account has a "kubernetes.io/enforce-mountable-secrets" annotation set to "true". The "kubernetes.io/enforce-mountable-secrets" annotation is deprecated since v1.32. Prefer separate namespaces to isolate access to mounted secrets. This field should not be used to find auto-generated service account token secrets for use outside of pods. Instead, tokens can be requested directly using the TokenRequest API, or service account token secrets can be manually created. More info: https://kubernetes.io/docs/concepts/configuration/secret
   */
  secrets?: IoK8SApiCoreV116[];
  [k: string]: unknown;
}
/**
 * ServiceAccountList is a list of ServiceAccount objects
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ServiceAccountList".
 */
export interface IoK8SApiCoreV1ServiceAccountList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of ServiceAccounts. More info: https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/
   */
  items: IoK8SApiCoreV1191[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * ServiceList holds a list of services.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.core.v1.ServiceList".
 */
export interface IoK8SApiCoreV1ServiceList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of services
   */
  items: IoK8SApiCoreV1186[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * Eviction evicts a pod from its node subject to certain policies and safety constraints. This is a subresource of Pod.  A request to cause such an eviction is created by POSTing to .../pods/<pod name>/evictions.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.policy.v1.Eviction".
 */
export interface IoK8SApiPolicyV1Eviction {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * DeleteOptions may be provided
   */
  deleteOptions?: IoK8SApimachineryPkgApisMetaV18;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * ObjectMeta describes the pod that is being evicted.
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  [k: string]: unknown;
}
/**
 * DeleteOptions may be provided when deleting an API object.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.DeleteOptions".
 */
export interface IoK8SApimachineryPkgApisMetaV18 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * When present, indicates that modifications should not be persisted. An invalid or unrecognized dryRun directive will result in an error response and no further processing of the request. Valid values are: - All: all dry run stages will be processed
   */
  dryRun?: string[];
  /**
   * The duration in seconds before the object should be deleted. Value must be non-negative integer. The value zero indicates delete immediately. If this value is nil, the default grace period for the specified type will be used. Defaults to a per object value if not specified. zero means delete immediately.
   */
  gracePeriodSeconds?: number;
  /**
   * if set to true, it will trigger an unsafe deletion of the resource in case the normal deletion flow fails with a corrupt object error. A resource is considered corrupt if it can not be retrieved from the underlying storage successfully because of a) its data can not be transformed e.g. decryption failure, or b) it fails to decode into an object. NOTE: unsafe deletion ignores finalizer constraints, skips precondition checks, and removes the object from the storage. WARNING: This may potentially break the cluster if the workload associated with the resource being unsafe-deleted relies on normal deletion flow. Use only if you REALLY know what you are doing. The default value is false, and the user must opt in to enable it
   */
  ignoreStoreReadErrorWithClusterBreakingPotential?: boolean;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Deprecated: please use the PropagationPolicy, this field will be deprecated in 1.7. Should the dependent objects be orphaned. If true/false, the "orphan" finalizer will be added to/removed from the object's finalizers list. Either this field or PropagationPolicy may be set, but not both.
   */
  orphanDependents?: boolean;
  /**
   * Must be fulfilled before a deletion is carried out. If not possible, a 409 Conflict status will be returned.
   */
  preconditions?: IoK8SApimachineryPkgApisMetaV19;
  /**
   * Whether and how garbage collection will be performed. Either this field or OrphanDependents may be set, but not both. The default policy is decided by the existing finalizer set in the metadata.finalizers and the resource-specific default policy. Acceptable values are: 'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the dependents in the background; 'Foreground' - a cascading policy that deletes all dependents in the foreground.
   */
  propagationPolicy?: string;
  [k: string]: unknown;
}
/**
 * Preconditions must be fulfilled before an operation (update, delete, etc.) is carried out.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.Preconditions".
 */
export interface IoK8SApimachineryPkgApisMetaV19 {
  /**
   * Specifies the target ResourceVersion
   */
  resourceVersion?: string;
  /**
   * Specifies the target UID.
   */
  uid?: string;
  [k: string]: unknown;
}
/**
 * APIResource specifies the name of a resource and whether it is namespaced.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.APIResource".
 */
export interface IoK8SApimachineryPkgApisMetaV110 {
  /**
   * categories is a list of the grouped resources this resource belongs to (e.g. 'all')
   */
  categories?: string[];
  /**
   * group is the preferred group of the resource.  Empty implies the group of the containing resource list. For subresources, this may have a different value, for example: Scale".
   */
  group?: string;
  /**
   * kind is the kind for the resource (e.g. 'Foo' is the kind for a resource 'foo')
   */
  kind: string;
  /**
   * name is the plural name of the resource.
   */
  name: string;
  /**
   * namespaced indicates if a resource is namespaced or not.
   */
  namespaced: boolean;
  /**
   * shortNames is a list of suggested short names of the resource.
   */
  shortNames?: string[];
  /**
   * singularName is the singular name of the resource.  This allows clients to handle plural and singular opaquely. The singularName is more correct for reporting status on a single item and both singular and plural are allowed from the kubectl CLI interface.
   */
  singularName: string;
  /**
   * The hash value of the storage version, the version this resource is converted to when written to the data store. Value must be treated as opaque by clients. Only equality comparison on the value is valid. This is an alpha feature and may change or be removed in the future. The field is populated by the apiserver only if the StorageVersionHash feature gate is enabled. This field will remain optional even if it graduates.
   */
  storageVersionHash?: string;
  /**
   * verbs is a list of supported kube verbs (this includes get, list, watch, create, update, patch, delete, deletecollection, and proxy)
   */
  verbs: string[];
  /**
   * version is the preferred version of the resource.  Empty implies the version of the containing resource list For subresources, this may have a different value, for example: v1 (while inside a v1beta1 version of the core resource's group)".
   */
  version?: string;
  [k: string]: unknown;
}
/**
 * APIResourceList is a list of APIResource, it is used to expose the name of the resources supported in a specific group and version, and if the resource is namespaced.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.APIResourceList".
 */
export interface IoK8SApimachineryPkgApisMetaV1APIResourceList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * groupVersion is the group and version this APIResourceList is for.
   */
  groupVersion: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * resources contains the name of the resources and if they are namespaced.
   */
  resources: IoK8SApimachineryPkgApisMetaV110[];
  [k: string]: unknown;
}
/**
 * Patch is provided to give a concrete name and type to the Kubernetes PATCH request body.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.Patch".
 */
export interface IoK8SApimachineryPkgApisMetaV1Patch {
  [k: string]: unknown;
}
/**
 * Status is a return value for calls that don't return other objects.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.Status".
 */
export interface IoK8SApimachineryPkgApisMetaV1Status {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Suggested HTTP return code for this status, 0 if not set.
   */
  code?: number;
  /**
   * Extended data associated with the reason.  Each reason may define its own extended details. This field is optional and the data returned is not guaranteed to conform to any schema except that defined by the reason type.
   */
  details?: IoK8SApimachineryPkgApisMetaV111;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * A human-readable description of the status of this operation.
   */
  message?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  /**
   * A machine-readable description of why this operation is in the "Failure" status. If this value is empty there is no information available. A Reason clarifies an HTTP status code but does not override it.
   */
  reason?: string;
  /**
   * Status of the operation. One of: "Success" or "Failure". More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: string;
  [k: string]: unknown;
}
/**
 * StatusDetails is a set of additional properties that MAY be set by the server to provide additional information about a response. The Reason field of a Status object defines what attributes will be set. Clients must ignore fields that do not match the defined type of each attribute, and should assume that any attribute may be empty, invalid, or under defined.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.StatusDetails".
 */
export interface IoK8SApimachineryPkgApisMetaV111 {
  /**
   * The Causes array includes more details associated with the StatusReason failure. Not all StatusReasons may provide detailed causes.
   */
  causes?: IoK8SApimachineryPkgApisMetaV112[];
  /**
   * The group attribute of the resource associated with the status StatusReason.
   */
  group?: string;
  /**
   * The kind attribute of the resource associated with the status StatusReason. On some operations may differ from the requested resource Kind. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * The name attribute of the resource associated with the status StatusReason (when there is a single name which can be described).
   */
  name?: string;
  /**
   * If specified, the time in seconds before the operation should be retried. Some errors may indicate the client must take an alternate action - for those errors this field may indicate how long to wait before taking the alternate action.
   */
  retryAfterSeconds?: number;
  /**
   * UID of the resource. (when there is a single resource which can be described). More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/names#uids
   */
  uid?: string;
  [k: string]: unknown;
}
/**
 * StatusCause provides more information about an api.Status failure, including cases when multiple errors are encountered.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.StatusCause".
 */
export interface IoK8SApimachineryPkgApisMetaV112 {
  /**
   * The field of the resource that has caused this error, as named by its JSON serialization. May include dot and postfix notation for nested attributes. Arrays are zero-indexed.  Fields may appear more than once in an array of causes due to fields having multiple errors. Optional.
   *
   * Examples:
   *   "name" - the field "name" on the current resource
   *   "items[0].name" - the field "name" on the first array entry in "items"
   */
  field?: string;
  /**
   * A human-readable description of the cause of the error.  This field may be presented as-is to a reader.
   */
  message?: string;
  /**
   * A machine-readable description of the cause of the error. If this value is empty there is no information available.
   */
  reason?: string;
  [k: string]: unknown;
}
/**
 * Event represents a single event to a watched resource.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.apis.meta.v1.WatchEvent".
 */
export interface IoK8SApimachineryPkgApisMetaV1WatchEvent {
  /**
   * Object is:
   *  * If Type is Added or Modified: the new state of the object.
   *  * If Type is Deleted: the state of the object immediately before deletion.
   *  * If Type is Error: *Status is recommended; other types may make sense
   *    depending on context.
   */
  object: IoK8SApimachineryPkgRuntime;
  type: string;
  [k: string]: unknown;
}
/**
 * RawExtension is used to hold extensions in external versions.
 *
 * To use this, make a field which has RawExtension as its type in your external, versioned struct, and Object in your internal struct. You also need to register your various plugin types.
 *
 * // Internal package:
 *
 * 	type MyAPIObject struct {
 * 		runtime.TypeMeta `json:",inline"`
 * 		MyPlugin runtime.Object `json:"myPlugin"`
 * 	}
 *
 * 	type PluginA struct {
 * 		AOption string `json:"aOption"`
 * 	}
 *
 * // External package:
 *
 * 	type MyAPIObject struct {
 * 		runtime.TypeMeta `json:",inline"`
 * 		MyPlugin runtime.RawExtension `json:"myPlugin"`
 * 	}
 *
 * 	type PluginA struct {
 * 		AOption string `json:"aOption"`
 * 	}
 *
 * // On the wire, the JSON will look something like this:
 *
 * 	{
 * 		"kind":"MyAPIObject",
 * 		"apiVersion":"v1",
 * 		"myPlugin": {
 * 			"kind":"PluginA",
 * 			"aOption":"foo",
 * 		},
 * 	}
 *
 * So what happens? Decode first uses json or yaml to unmarshal the serialized data into your external MyAPIObject. That causes the raw JSON to be stored, but not unpacked. The next step is to copy (using pkg/conversion) into the internal struct. The runtime package's DefaultScheme has conversion functions installed which will unpack the JSON stored in RawExtension, turning it into the correct object type, and storing it in the Object. (TODO: In the case where the object is of an unknown type, a runtime.Unknown object will be created and stored.)
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.apimachinery.pkg.runtime.RawExtension".
 */
export interface IoK8SApimachineryPkgRuntime {
  [k: string]: unknown;
}
/**
 * ControllerRevision implements an immutable snapshot of state data. Clients are responsible for serializing and deserializing the objects that contain their internal state. Once a ControllerRevision has been successfully created, it can not be updated. The API Server will fail validation of all requests that attempt to mutate the Data field. ControllerRevisions may, however, be deleted. Note that, due to its use by both the DaemonSet and StatefulSet controllers for update and rollback, this object is beta. However, it may be subject to name and representation changes in future releases, and clients should not depend on its stability. It is primarily for internal use by controllers.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.ControllerRevision".
 */
export interface IoK8SApiAppsV1 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Data is the serialized representation of the state.
   */
  data?: IoK8SApimachineryPkgRuntime;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Revision indicates the revision of the state represented by Data.
   */
  revision: number;
  [k: string]: unknown;
}
/**
 * ControllerRevisionList is a resource containing a list of ControllerRevision objects.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.ControllerRevisionList".
 */
export interface IoK8SApiAppsV1ControllerRevisionList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Items is the list of ControllerRevisions
   */
  items: IoK8SApiAppsV1[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * DaemonSet represents the configuration of a daemon set.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DaemonSet".
 */
export interface IoK8SApiAppsV11 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * The desired behavior of this daemon set. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiAppsV12;
  /**
   * The current status of this daemon set. This data may be out of date by some window of time. Populated by the system. Read-only. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiAppsV15;
  [k: string]: unknown;
}
/**
 * DaemonSetSpec is the specification of a daemon set.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DaemonSetSpec".
 */
export interface IoK8SApiAppsV12 {
  /**
   * The minimum number of seconds for which a newly created DaemonSet pod should be ready without any of its container crashing, for it to be considered available. Defaults to 0 (pod will be considered available as soon as it is ready).
   */
  minReadySeconds?: number;
  /**
   * The number of old history to retain to allow rollback. This is a pointer to distinguish between explicit zero and not specified. Defaults to 10.
   */
  revisionHistoryLimit?: number;
  /**
   * A label query over pods that are managed by the daemon set. Must match in order to be controlled. It must match the pod template's labels. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#label-selectors
   */
  selector: IoK8SApimachineryPkgApisMetaV14;
  /**
   * An object that describes the pod that will be created. The DaemonSet will create exactly one copy of this pod on every node that matches the template's node selector (or on every node if no node selector is specified). The only allowed template.spec.restartPolicy value is "Always". More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller#pod-template
   */
  template: IoK8SApiCoreV1175;
  /**
   * An update strategy to replace existing DaemonSet pods with new pods.
   */
  updateStrategy?: IoK8SApiAppsV13;
  [k: string]: unknown;
}
/**
 * DaemonSetUpdateStrategy is a struct used to control the update strategy for a DaemonSet.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DaemonSetUpdateStrategy".
 */
export interface IoK8SApiAppsV13 {
  /**
   * Rolling update config params. Present only if type = "RollingUpdate".
   */
  rollingUpdate?: IoK8SApiAppsV14;
  /**
   * Type of daemon set update. Can be "RollingUpdate" or "OnDelete". Default is RollingUpdate.
   *
   * Possible enum values:
   *  - `"OnDelete"` Replace the old daemons only when it's killed
   *  - `"RollingUpdate"` Replace the old daemons by new ones using rolling update i.e replace them on each node one after the other.
   */
  type?: "OnDelete" | "RollingUpdate";
  [k: string]: unknown;
}
/**
 * Spec to control the desired behavior of daemon set rolling update.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.RollingUpdateDaemonSet".
 */
export interface IoK8SApiAppsV14 {
  /**
   * The maximum number of nodes with an existing available DaemonSet pod that can have an updated DaemonSet pod during during an update. Value can be an absolute number (ex: 5) or a percentage of desired pods (ex: 10%). This can not be 0 if MaxUnavailable is 0. Absolute number is calculated from percentage by rounding up to a minimum of 1. Default value is 0. Example: when this is set to 30%, at most 30% of the total number of nodes that should be running the daemon pod (i.e. status.desiredNumberScheduled) can have their a new pod created before the old pod is marked as deleted. The update starts by launching new pods on 30% of nodes. Once an updated pod is available (Ready for at least minReadySeconds) the old DaemonSet pod on that node is marked deleted. If the old pod becomes unavailable for any reason (Ready transitions to false, is evicted, or is drained) an updated pod is immediatedly created on that node without considering surge limits. Allowing surge implies the possibility that the resources consumed by the daemonset on any given node can double if the readiness check fails, and so resource intensive daemonsets should take into account that they may cause evictions during disruption.
   */
  maxSurge?: IoK8SApimachineryPkgUtilIntstrIntOrString;
  /**
   * The maximum number of DaemonSet pods that can be unavailable during the update. Value can be an absolute number (ex: 5) or a percentage of total number of DaemonSet pods at the start of the update (ex: 10%). Absolute number is calculated from percentage by rounding up. This cannot be 0 if MaxSurge is 0 Default value is 1. Example: when this is set to 30%, at most 30% of the total number of nodes that should be running the daemon pod (i.e. status.desiredNumberScheduled) can have their pods stopped for an update at any given time. The update starts by stopping at most 30% of those DaemonSet pods and then brings up new DaemonSet pods in their place. Once the new pods are available, it then proceeds onto other DaemonSet pods, thus ensuring that at least 70% of original number of DaemonSet pods are available at all times during the update.
   */
  maxUnavailable?: IoK8SApimachineryPkgUtilIntstrIntOrString;
  [k: string]: unknown;
}
/**
 * DaemonSetStatus represents the current status of a daemon set.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DaemonSetStatus".
 */
export interface IoK8SApiAppsV15 {
  /**
   * Count of hash collisions for the DaemonSet. The DaemonSet controller uses this field as a collision avoidance mechanism when it needs to create the name for the newest ControllerRevision.
   */
  collisionCount?: number;
  /**
   * Represents the latest available observations of a DaemonSet's current state.
   */
  conditions?: IoK8SApiAppsV16[];
  /**
   * The number of nodes that are running at least 1 daemon pod and are supposed to run the daemon pod. More info: https://kubernetes.io/docs/concepts/workloads/controllers/daemonset/
   */
  currentNumberScheduled: number;
  /**
   * The total number of nodes that should be running the daemon pod (including nodes correctly running the daemon pod). More info: https://kubernetes.io/docs/concepts/workloads/controllers/daemonset/
   */
  desiredNumberScheduled: number;
  /**
   * The number of nodes that should be running the daemon pod and have one or more of the daemon pod running and available (ready for at least spec.minReadySeconds)
   */
  numberAvailable?: number;
  /**
   * The number of nodes that are running the daemon pod, but are not supposed to run the daemon pod. More info: https://kubernetes.io/docs/concepts/workloads/controllers/daemonset/
   */
  numberMisscheduled: number;
  /**
   * numberReady is the number of nodes that should be running the daemon pod and have one or more of the daemon pod running with a Ready Condition.
   */
  numberReady: number;
  /**
   * The number of nodes that should be running the daemon pod and have none of the daemon pod running and available (ready for at least spec.minReadySeconds)
   */
  numberUnavailable?: number;
  /**
   * The most recent generation observed by the daemon set controller.
   */
  observedGeneration?: number;
  /**
   * The total number of nodes that are running updated daemon pod
   */
  updatedNumberScheduled?: number;
  [k: string]: unknown;
}
/**
 * DaemonSetCondition describes the state of a DaemonSet at a certain point.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DaemonSetCondition".
 */
export interface IoK8SApiAppsV16 {
  /**
   * Last time the condition transitioned from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * A human readable message indicating details about the transition.
   */
  message?: string;
  /**
   * The reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * Type of DaemonSet condition.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * DaemonSetList is a collection of daemon sets.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DaemonSetList".
 */
export interface IoK8SApiAppsV1DaemonSetList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * A list of daemon sets.
   */
  items: IoK8SApiAppsV11[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * Deployment enables declarative updates for Pods and ReplicaSets.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.Deployment".
 */
export interface IoK8SApiAppsV17 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Specification of the desired behavior of the Deployment.
   */
  spec?: IoK8SApiAppsV18;
  /**
   * Most recently observed status of the Deployment.
   */
  status?: IoK8SApiAppsV111;
  [k: string]: unknown;
}
/**
 * DeploymentSpec is the specification of the desired behavior of the Deployment.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DeploymentSpec".
 */
export interface IoK8SApiAppsV18 {
  /**
   * Minimum number of seconds for which a newly created pod should be ready without any of its container crashing, for it to be considered available. Defaults to 0 (pod will be considered available as soon as it is ready)
   */
  minReadySeconds?: number;
  /**
   * Indicates that the deployment is paused.
   */
  paused?: boolean;
  /**
   * The maximum time in seconds for a deployment to make progress before it is considered to be failed. The deployment controller will continue to process failed deployments and a condition with a ProgressDeadlineExceeded reason will be surfaced in the deployment status. Note that progress will not be estimated during the time a deployment is paused. Defaults to 600s.
   */
  progressDeadlineSeconds?: number;
  /**
   * Number of desired pods. This is a pointer to distinguish between explicit zero and not specified. Defaults to 1.
   */
  replicas?: number;
  /**
   * The number of old ReplicaSets to retain to allow rollback. This is a pointer to distinguish between explicit zero and not specified. Defaults to 10.
   */
  revisionHistoryLimit?: number;
  /**
   * Label selector for pods. Existing ReplicaSets whose pods are selected by this will be the ones affected by this deployment. It must match the pod template's labels.
   */
  selector: IoK8SApimachineryPkgApisMetaV14;
  /**
   * The deployment strategy to use to replace existing pods with new ones.
   */
  strategy?: IoK8SApiAppsV19;
  /**
   * Template describes the pods that will be created. The only allowed template.spec.restartPolicy value is "Always".
   */
  template: IoK8SApiCoreV1175;
  [k: string]: unknown;
}
/**
 * DeploymentStrategy describes how to replace existing pods with new ones.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DeploymentStrategy".
 */
export interface IoK8SApiAppsV19 {
  /**
   * Rolling update config params. Present only if DeploymentStrategyType = RollingUpdate.
   */
  rollingUpdate?: IoK8SApiAppsV110;
  /**
   * Type of deployment. Can be "Recreate" or "RollingUpdate". Default is RollingUpdate.
   *
   * Possible enum values:
   *  - `"Recreate"` Kill all existing pods before creating new ones.
   *  - `"RollingUpdate"` Replace the old ReplicaSets by new one using rolling update i.e gradually scale down the old ReplicaSets and scale up the new one.
   */
  type?: "Recreate" | "RollingUpdate";
  [k: string]: unknown;
}
/**
 * Spec to control the desired behavior of rolling update.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.RollingUpdateDeployment".
 */
export interface IoK8SApiAppsV110 {
  /**
   * The maximum number of pods that can be scheduled above the desired number of pods. Value can be an absolute number (ex: 5) or a percentage of desired pods (ex: 10%). This can not be 0 if MaxUnavailable is 0. Absolute number is calculated from percentage by rounding up. Defaults to 25%. Example: when this is set to 30%, the new ReplicaSet can be scaled up immediately when the rolling update starts, such that the total number of old and new pods do not exceed 130% of desired pods. Once old pods have been killed, new ReplicaSet can be scaled up further, ensuring that total number of pods running at any time during the update is at most 130% of desired pods.
   */
  maxSurge?: IoK8SApimachineryPkgUtilIntstrIntOrString;
  /**
   * The maximum number of pods that can be unavailable during the update. Value can be an absolute number (ex: 5) or a percentage of desired pods (ex: 10%). Absolute number is calculated from percentage by rounding down. This can not be 0 if MaxSurge is 0. Defaults to 25%. Example: when this is set to 30%, the old ReplicaSet can be scaled down to 70% of desired pods immediately when the rolling update starts. Once new pods are ready, old ReplicaSet can be scaled down further, followed by scaling up the new ReplicaSet, ensuring that the total number of pods available at all times during the update is at least 70% of desired pods.
   */
  maxUnavailable?: IoK8SApimachineryPkgUtilIntstrIntOrString;
  [k: string]: unknown;
}
/**
 * DeploymentStatus is the most recently observed status of the Deployment.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DeploymentStatus".
 */
export interface IoK8SApiAppsV111 {
  /**
   * Total number of available pods (ready for at least minReadySeconds) targeted by this deployment.
   */
  availableReplicas?: number;
  /**
   * Count of hash collisions for the Deployment. The Deployment controller uses this field as a collision avoidance mechanism when it needs to create the name for the newest ReplicaSet.
   */
  collisionCount?: number;
  /**
   * Represents the latest available observations of a deployment's current state.
   */
  conditions?: IoK8SApiAppsV112[];
  /**
   * The generation observed by the deployment controller.
   */
  observedGeneration?: number;
  /**
   * readyReplicas is the number of pods targeted by this Deployment with a Ready Condition.
   */
  readyReplicas?: number;
  /**
   * Total number of non-terminated pods targeted by this deployment (their labels match the selector).
   */
  replicas?: number;
  /**
   * Total number of unavailable pods targeted by this deployment. This is the total number of pods that are still required for the deployment to have 100% available capacity. They may either be pods that are running but not yet available or pods that still have not been created.
   */
  unavailableReplicas?: number;
  /**
   * Total number of non-terminated pods targeted by this deployment that have the desired template spec.
   */
  updatedReplicas?: number;
  [k: string]: unknown;
}
/**
 * DeploymentCondition describes the state of a deployment at a certain point.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DeploymentCondition".
 */
export interface IoK8SApiAppsV112 {
  /**
   * Last time the condition transitioned from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * The last time this condition was updated.
   */
  lastUpdateTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * A human readable message indicating details about the transition.
   */
  message?: string;
  /**
   * The reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * Type of deployment condition.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * DeploymentList is a list of Deployments.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.DeploymentList".
 */
export interface IoK8SApiAppsV1DeploymentList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Items is the list of Deployments.
   */
  items: IoK8SApiAppsV17[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata.
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * ReplicaSet ensures that a specified number of pod replicas are running at any given time.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.ReplicaSet".
 */
export interface IoK8SApiAppsV113 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * If the Labels of a ReplicaSet are empty, they are defaulted to be the same as the Pod(s) that the ReplicaSet manages. Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec defines the specification of the desired behavior of the ReplicaSet. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiAppsV114;
  /**
   * Status is the most recently observed status of the ReplicaSet. This data may be out of date by some window of time. Populated by the system. Read-only. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiAppsV115;
  [k: string]: unknown;
}
/**
 * ReplicaSetSpec is the specification of a ReplicaSet.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.ReplicaSetSpec".
 */
export interface IoK8SApiAppsV114 {
  /**
   * Minimum number of seconds for which a newly created pod should be ready without any of its container crashing, for it to be considered available. Defaults to 0 (pod will be considered available as soon as it is ready)
   */
  minReadySeconds?: number;
  /**
   * Replicas is the number of desired replicas. This is a pointer to distinguish between explicit zero and unspecified. Defaults to 1. More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller/#what-is-a-replicationcontroller
   */
  replicas?: number;
  /**
   * Selector is a label query over pods that should match the replica count. Label keys and values that must match in order to be controlled by this replica set. It must match the pod template's labels. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#label-selectors
   */
  selector: IoK8SApimachineryPkgApisMetaV14;
  /**
   * Template is the object that describes the pod that will be created if insufficient replicas are detected. More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller#pod-template
   */
  template?: IoK8SApiCoreV1175;
  [k: string]: unknown;
}
/**
 * ReplicaSetStatus represents the current status of a ReplicaSet.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.ReplicaSetStatus".
 */
export interface IoK8SApiAppsV115 {
  /**
   * The number of available replicas (ready for at least minReadySeconds) for this replica set.
   */
  availableReplicas?: number;
  /**
   * Represents the latest available observations of a replica set's current state.
   */
  conditions?: IoK8SApiAppsV116[];
  /**
   * The number of pods that have labels matching the labels of the pod template of the replicaset.
   */
  fullyLabeledReplicas?: number;
  /**
   * ObservedGeneration reflects the generation of the most recently observed ReplicaSet.
   */
  observedGeneration?: number;
  /**
   * readyReplicas is the number of pods targeted by this ReplicaSet with a Ready Condition.
   */
  readyReplicas?: number;
  /**
   * Replicas is the most recently observed number of replicas. More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller/#what-is-a-replicationcontroller
   */
  replicas: number;
  [k: string]: unknown;
}
/**
 * ReplicaSetCondition describes the state of a replica set at a certain point.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.ReplicaSetCondition".
 */
export interface IoK8SApiAppsV116 {
  /**
   * The last time the condition transitioned from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * A human readable message indicating details about the transition.
   */
  message?: string;
  /**
   * The reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * Type of replica set condition.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * ReplicaSetList is a collection of ReplicaSets.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.ReplicaSetList".
 */
export interface IoK8SApiAppsV1ReplicaSetList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * List of ReplicaSets. More info: https://kubernetes.io/docs/concepts/workloads/controllers/replicationcontroller
   */
  items: IoK8SApiAppsV113[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * RollingUpdateStatefulSetStrategy is used to communicate parameter for RollingUpdateStatefulSetStrategyType.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.RollingUpdateStatefulSetStrategy".
 */
export interface IoK8SApiAppsV117 {
  /**
   * The maximum number of pods that can be unavailable during the update. Value can be an absolute number (ex: 5) or a percentage of desired pods (ex: 10%). Absolute number is calculated from percentage by rounding up. This can not be 0. Defaults to 1. This field is alpha-level and is only honored by servers that enable the MaxUnavailableStatefulSet feature. The field applies to all pods in the range 0 to Replicas-1. That means if there is any unavailable pod in the range 0 to Replicas-1, it will be counted towards MaxUnavailable.
   */
  maxUnavailable?: IoK8SApimachineryPkgUtilIntstrIntOrString;
  /**
   * Partition indicates the ordinal at which the StatefulSet should be partitioned for updates. During a rolling update, all pods from ordinal Replicas-1 to Partition are updated. All pods from ordinal Partition-1 to 0 remain untouched. This is helpful in being able to do a canary based deployment. The default value is 0.
   */
  partition?: number;
  [k: string]: unknown;
}
/**
 * StatefulSet represents a set of pods with consistent identities. Identities are defined as:
 *   - Network: A single stable DNS and hostname.
 *   - Storage: As many VolumeClaims as requested.
 *
 * The StatefulSet guarantees that a given network identity will always map to the same storage identity.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.StatefulSet".
 */
export interface IoK8SApiAppsV118 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Spec defines the desired identities of pods in this set.
   */
  spec?: IoK8SApiAppsV119;
  /**
   * Status is the current status of Pods in this StatefulSet. This data may be out of date by some window of time.
   */
  status?: IoK8SApiAppsV123;
  [k: string]: unknown;
}
/**
 * A StatefulSetSpec is the specification of a StatefulSet.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.StatefulSetSpec".
 */
export interface IoK8SApiAppsV119 {
  /**
   * Minimum number of seconds for which a newly created pod should be ready without any of its container crashing for it to be considered available. Defaults to 0 (pod will be considered available as soon as it is ready)
   */
  minReadySeconds?: number;
  /**
   * ordinals controls the numbering of replica indices in a StatefulSet. The default ordinals behavior assigns a "0" index to the first replica and increments the index by one for each additional replica requested.
   */
  ordinals?: IoK8SApiAppsV120;
  /**
   * persistentVolumeClaimRetentionPolicy describes the lifecycle of persistent volume claims created from volumeClaimTemplates. By default, all persistent volume claims are created as needed and retained until manually deleted. This policy allows the lifecycle to be altered, for example by deleting persistent volume claims when their stateful set is deleted, or when their pod is scaled down.
   */
  persistentVolumeClaimRetentionPolicy?: IoK8SApiAppsV121;
  /**
   * podManagementPolicy controls how pods are created during initial scale up, when replacing pods on nodes, or when scaling down. The default policy is `OrderedReady`, where pods are created in increasing order (pod-0, then pod-1, etc) and the controller will wait until each pod is ready before continuing. When scaling down, the pods are removed in the opposite order. The alternative policy is `Parallel` which will create pods in parallel to match the desired scale without waiting, and on scale down will delete all pods at once.
   *
   * Possible enum values:
   *  - `"OrderedReady"` will create pods in strictly increasing order on scale up and strictly decreasing order on scale down, progressing only when the previous pod is ready or terminated. At most one pod will be changed at any time.
   *  - `"Parallel"` will create and delete pods as soon as the stateful set replica count is changed, and will not wait for pods to be ready or complete termination.
   */
  podManagementPolicy?: "OrderedReady" | "Parallel";
  /**
   * replicas is the desired number of replicas of the given Template. These are replicas in the sense that they are instantiations of the same Template, but individual replicas also have a consistent identity. If unspecified, defaults to 1.
   */
  replicas?: number;
  /**
   * revisionHistoryLimit is the maximum number of revisions that will be maintained in the StatefulSet's revision history. The revision history consists of all revisions not represented by a currently applied StatefulSetSpec version. The default value is 10.
   */
  revisionHistoryLimit?: number;
  /**
   * selector is a label query over pods that should match the replica count. It must match the pod template's labels. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#label-selectors
   */
  selector: IoK8SApimachineryPkgApisMetaV14;
  /**
   * serviceName is the name of the service that governs this StatefulSet. This service must exist before the StatefulSet, and is responsible for the network identity of the set. Pods get DNS/hostnames that follow the pattern: pod-specific-string.serviceName.default.svc.cluster.local where "pod-specific-string" is managed by the StatefulSet controller.
   */
  serviceName: string;
  /**
   * template is the object that describes the pod that will be created if insufficient replicas are detected. Each pod stamped out by the StatefulSet will fulfill this Template, but have a unique identity from the rest of the StatefulSet. Each pod will be named with the format <statefulsetname>-<podindex>. For example, a pod in a StatefulSet named "web" with index number "3" would be named "web-3". The only allowed template.spec.restartPolicy value is "Always".
   */
  template: IoK8SApiCoreV1175;
  /**
   * updateStrategy indicates the StatefulSetUpdateStrategy that will be employed to update Pods in the StatefulSet when a revision is made to Template.
   */
  updateStrategy?: IoK8SApiAppsV122;
  /**
   * volumeClaimTemplates is a list of claims that pods are allowed to reference. The StatefulSet controller is responsible for mapping network identities to claims in a way that maintains the identity of a pod. Every claim in this list must have at least one matching (by name) volumeMount in one container in the template. A claim in this list takes precedence over any volumes in the template, with the same name.
   */
  volumeClaimTemplates?: IoK8SApiCoreV1145[];
  [k: string]: unknown;
}
/**
 * StatefulSetOrdinals describes the policy used for replica ordinal assignment in this StatefulSet.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.StatefulSetOrdinals".
 */
export interface IoK8SApiAppsV120 {
  /**
   * start is the number representing the first replica's index. It may be used to number replicas from an alternate index (eg: 1-indexed) over the default 0-indexed names, or to orchestrate progressive movement of replicas from one StatefulSet to another. If set, replica indices will be in the range:
   *   [.spec.ordinals.start, .spec.ordinals.start + .spec.replicas).
   * If unset, defaults to 0. Replica indices will be in the range:
   *   [0, .spec.replicas).
   */
  start?: number;
  [k: string]: unknown;
}
/**
 * StatefulSetPersistentVolumeClaimRetentionPolicy describes the policy used for PVCs created from the StatefulSet VolumeClaimTemplates.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.StatefulSetPersistentVolumeClaimRetentionPolicy".
 */
export interface IoK8SApiAppsV121 {
  /**
   * WhenDeleted specifies what happens to PVCs created from StatefulSet VolumeClaimTemplates when the StatefulSet is deleted. The default policy of `Retain` causes PVCs to not be affected by StatefulSet deletion. The `Delete` policy causes those PVCs to be deleted.
   */
  whenDeleted?: string;
  /**
   * WhenScaled specifies what happens to PVCs created from StatefulSet VolumeClaimTemplates when the StatefulSet is scaled down. The default policy of `Retain` causes PVCs to not be affected by a scaledown. The `Delete` policy causes the associated PVCs for any excess pods above the replica count to be deleted.
   */
  whenScaled?: string;
  [k: string]: unknown;
}
/**
 * StatefulSetUpdateStrategy indicates the strategy that the StatefulSet controller will use to perform updates. It includes any additional parameters necessary to perform the update for the indicated strategy.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.StatefulSetUpdateStrategy".
 */
export interface IoK8SApiAppsV122 {
  /**
   * RollingUpdate is used to communicate parameters when Type is RollingUpdateStatefulSetStrategyType.
   */
  rollingUpdate?: IoK8SApiAppsV117;
  /**
   * Type indicates the type of the StatefulSetUpdateStrategy. Default is RollingUpdate.
   *
   * Possible enum values:
   *  - `"OnDelete"` triggers the legacy behavior. Version tracking and ordered rolling restarts are disabled. Pods are recreated from the StatefulSetSpec when they are manually deleted. When a scale operation is performed with this strategy,specification version indicated by the StatefulSet's currentRevision.
   *  - `"RollingUpdate"` indicates that update will be applied to all Pods in the StatefulSet with respect to the StatefulSet ordering constraints. When a scale operation is performed with this strategy, new Pods will be created from the specification version indicated by the StatefulSet's updateRevision.
   */
  type?: "OnDelete" | "RollingUpdate";
  [k: string]: unknown;
}
/**
 * StatefulSetStatus represents the current state of a StatefulSet.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.StatefulSetStatus".
 */
export interface IoK8SApiAppsV123 {
  /**
   * Total number of available pods (ready for at least minReadySeconds) targeted by this statefulset.
   */
  availableReplicas?: number;
  /**
   * collisionCount is the count of hash collisions for the StatefulSet. The StatefulSet controller uses this field as a collision avoidance mechanism when it needs to create the name for the newest ControllerRevision.
   */
  collisionCount?: number;
  /**
   * Represents the latest available observations of a statefulset's current state.
   */
  conditions?: IoK8SApiAppsV124[];
  /**
   * currentReplicas is the number of Pods created by the StatefulSet controller from the StatefulSet version indicated by currentRevision.
   */
  currentReplicas?: number;
  /**
   * currentRevision, if not empty, indicates the version of the StatefulSet used to generate Pods in the sequence [0,currentReplicas).
   */
  currentRevision?: string;
  /**
   * observedGeneration is the most recent generation observed for this StatefulSet. It corresponds to the StatefulSet's generation, which is updated on mutation by the API Server.
   */
  observedGeneration?: number;
  /**
   * readyReplicas is the number of pods created for this StatefulSet with a Ready Condition.
   */
  readyReplicas?: number;
  /**
   * replicas is the number of Pods created by the StatefulSet controller.
   */
  replicas: number;
  /**
   * updateRevision, if not empty, indicates the version of the StatefulSet used to generate Pods in the sequence [replicas-updatedReplicas,replicas)
   */
  updateRevision?: string;
  /**
   * updatedReplicas is the number of Pods created by the StatefulSet controller from the StatefulSet version indicated by updateRevision.
   */
  updatedReplicas?: number;
  [k: string]: unknown;
}
/**
 * StatefulSetCondition describes the state of a statefulset at a certain point.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.StatefulSetCondition".
 */
export interface IoK8SApiAppsV124 {
  /**
   * Last time the condition transitioned from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * A human readable message indicating details about the transition.
   */
  message?: string;
  /**
   * The reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * Type of statefulset condition.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * StatefulSetList is a collection of StatefulSets.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.apps.v1.StatefulSetList".
 */
export interface IoK8SApiAppsV1StatefulSetList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Items is the list of stateful sets.
   */
  items: IoK8SApiAppsV118[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * CronJob represents the configuration of a single cron job.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.CronJob".
 */
export interface IoK8SApiBatchV1 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Specification of the desired behavior of a cron job, including the schedule. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiBatchV11;
  /**
   * Current status of a cron job. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiBatchV110;
  [k: string]: unknown;
}
/**
 * CronJobSpec describes how the job execution will look like and when it will actually run.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.CronJobSpec".
 */
export interface IoK8SApiBatchV11 {
  /**
   * Specifies how to treat concurrent executions of a Job. Valid values are:
   *
   * - "Allow" (default): allows CronJobs to run concurrently; - "Forbid": forbids concurrent runs, skipping next run if previous run hasn't finished yet; - "Replace": cancels currently running job and replaces it with a new one
   *
   * Possible enum values:
   *  - `"Allow"` allows CronJobs to run concurrently.
   *  - `"Forbid"` forbids concurrent runs, skipping next run if previous hasn't finished yet.
   *  - `"Replace"` cancels currently running job and replaces it with a new one.
   */
  concurrencyPolicy?: "Allow" | "Forbid" | "Replace";
  /**
   * The number of failed finished jobs to retain. Value must be non-negative integer. Defaults to 1.
   */
  failedJobsHistoryLimit?: number;
  /**
   * Specifies the job that will be created when executing a CronJob.
   */
  jobTemplate: IoK8SApiBatchV12;
  /**
   * The schedule in Cron format, see https://en.wikipedia.org/wiki/Cron.
   */
  schedule: string;
  /**
   * Optional deadline in seconds for starting the job if it misses scheduled time for any reason.  Missed jobs executions will be counted as failed ones.
   */
  startingDeadlineSeconds?: number;
  /**
   * The number of successful finished jobs to retain. Value must be non-negative integer. Defaults to 3.
   */
  successfulJobsHistoryLimit?: number;
  /**
   * This flag tells the controller to suspend subsequent executions, it does not apply to already started executions.  Defaults to false.
   */
  suspend?: boolean;
  /**
   * The time zone name for the given schedule, see https://en.wikipedia.org/wiki/List_of_tz_database_time_zones. If not specified, this will default to the time zone of the kube-controller-manager process. The set of valid time zone names and the time zone offset is loaded from the system-wide time zone database by the API server during CronJob validation and the controller manager during execution. If no system-wide time zone database can be found a bundled version of the database is used instead. If the time zone name becomes invalid during the lifetime of a CronJob or due to a change in host configuration, the controller will stop creating new new Jobs and will create a system event with the reason UnknownTimeZone. More information can be found in https://kubernetes.io/docs/concepts/workloads/controllers/cron-jobs/#time-zones
   */
  timeZone?: string;
  [k: string]: unknown;
}
/**
 * JobTemplateSpec describes the data a Job should have when created from a template
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.JobTemplateSpec".
 */
export interface IoK8SApiBatchV12 {
  /**
   * Standard object's metadata of the jobs created from this template. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Specification of the desired behavior of the job. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiBatchV13;
  [k: string]: unknown;
}
/**
 * JobSpec describes how the job execution will look like.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.JobSpec".
 */
export interface IoK8SApiBatchV13 {
  /**
   * Specifies the duration in seconds relative to the startTime that the job may be continuously active before the system tries to terminate it; value must be positive integer. If a Job is suspended (at creation or through an update), this timer will effectively be stopped and reset when the Job is resumed again.
   */
  activeDeadlineSeconds?: number;
  /**
   * Specifies the number of retries before marking this job failed. Defaults to 6
   */
  backoffLimit?: number;
  /**
   * Specifies the limit for the number of retries within an index before marking this index as failed. When enabled the number of failures per index is kept in the pod's batch.kubernetes.io/job-index-failure-count annotation. It can only be set when Job's completionMode=Indexed, and the Pod's restart policy is Never. The field is immutable. This field is beta-level. It can be used when the `JobBackoffLimitPerIndex` feature gate is enabled (enabled by default).
   */
  backoffLimitPerIndex?: number;
  /**
   * completionMode specifies how Pod completions are tracked. It can be `NonIndexed` (default) or `Indexed`.
   *
   * `NonIndexed` means that the Job is considered complete when there have been .spec.completions successfully completed Pods. Each Pod completion is homologous to each other.
   *
   * `Indexed` means that the Pods of a Job get an associated completion index from 0 to (.spec.completions - 1), available in the annotation batch.kubernetes.io/job-completion-index. The Job is considered complete when there is one successfully completed Pod for each index. When value is `Indexed`, .spec.completions must be specified and `.spec.parallelism` must be less than or equal to 10^5. In addition, The Pod name takes the form `$(job-name)-$(index)-$(random-string)`, the Pod hostname takes the form `$(job-name)-$(index)`.
   *
   * More completion modes can be added in the future. If the Job controller observes a mode that it doesn't recognize, which is possible during upgrades due to version skew, the controller skips updates for the Job.
   *
   * Possible enum values:
   *  - `"Indexed"` is a Job completion mode. In this mode, the Pods of a Job get an associated completion index from 0 to (.spec.completions - 1). The Job is considered complete when a Pod completes for each completion index.
   *  - `"NonIndexed"` is a Job completion mode. In this mode, the Job is considered complete when there have been .spec.completions successfully completed Pods. Pod completions are homologous to each other.
   */
  completionMode?: "Indexed" | "NonIndexed";
  /**
   * Specifies the desired number of successfully finished pods the job should be run with.  Setting to null means that the success of any pod signals the success of all pods, and allows parallelism to have any positive value.  Setting to 1 means that parallelism is limited to 1 and the success of that pod signals the success of the job. More info: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/
   */
  completions?: number;
  /**
   * ManagedBy field indicates the controller that manages a Job. The k8s Job controller reconciles jobs which don't have this field at all or the field value is the reserved string `kubernetes.io/job-controller`, but skips reconciling Jobs with a custom value for this field. The value must be a valid domain-prefixed path (e.g. acme.io/foo) - all characters before the first "/" must be a valid subdomain as defined by RFC 1123. All characters trailing the first "/" must be valid HTTP Path characters as defined by RFC 3986. The value cannot exceed 63 characters. This field is immutable.
   *
   * This field is beta-level. The job controller accepts setting the field when the feature gate JobManagedBy is enabled (enabled by default).
   */
  managedBy?: string;
  /**
   * manualSelector controls generation of pod labels and pod selectors. Leave `manualSelector` unset unless you are certain what you are doing. When false or unset, the system pick labels unique to this job and appends those labels to the pod template.  When true, the user is responsible for picking unique labels and specifying the selector.  Failure to pick a unique label may cause this and other jobs to not function correctly.  However, You may see `manualSelector=true` in jobs that were created with the old `extensions/v1beta1` API. More info: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/#specifying-your-own-pod-selector
   */
  manualSelector?: boolean;
  /**
   * Specifies the maximal number of failed indexes before marking the Job as failed, when backoffLimitPerIndex is set. Once the number of failed indexes exceeds this number the entire Job is marked as Failed and its execution is terminated. When left as null the job continues execution of all of its indexes and is marked with the `Complete` Job condition. It can only be specified when backoffLimitPerIndex is set. It can be null or up to completions. It is required and must be less than or equal to 10^4 when is completions greater than 10^5. This field is beta-level. It can be used when the `JobBackoffLimitPerIndex` feature gate is enabled (enabled by default).
   */
  maxFailedIndexes?: number;
  /**
   * Specifies the maximum desired number of pods the job should run at any given time. The actual number of pods running in steady state will be less than this number when ((.spec.completions - .status.successful) < .spec.parallelism), i.e. when the work left to do is less than max parallelism. More info: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/
   */
  parallelism?: number;
  /**
   * Specifies the policy of handling failed pods. In particular, it allows to specify the set of actions and conditions which need to be satisfied to take the associated action. If empty, the default behaviour applies - the counter of failed pods, represented by the jobs's .status.failed field, is incremented and it is checked against the backoffLimit. This field cannot be used in combination with restartPolicy=OnFailure.
   */
  podFailurePolicy?: IoK8SApiBatchV14;
  /**
   * podReplacementPolicy specifies when to create replacement Pods. Possible values are: - TerminatingOrFailed means that we recreate pods
   *   when they are terminating (has a metadata.deletionTimestamp) or failed.
   * - Failed means to wait until a previously created Pod is fully terminated (has phase
   *   Failed or Succeeded) before creating a replacement Pod.
   *
   * When using podFailurePolicy, Failed is the the only allowed value. TerminatingOrFailed and Failed are allowed values when podFailurePolicy is not in use. This is an beta field. To use this, enable the JobPodReplacementPolicy feature toggle. This is on by default.
   *
   * Possible enum values:
   *  - `"Failed"` means to wait until a previously created Pod is fully terminated (has phase Failed or Succeeded) before creating a replacement Pod.
   *  - `"TerminatingOrFailed"` means that we recreate pods when they are terminating (has a metadata.deletionTimestamp) or failed.
   */
  podReplacementPolicy?: "Failed" | "TerminatingOrFailed";
  /**
   * A label query over pods that should match the pod count. Normally, the system sets this field for you. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/#label-selectors
   */
  selector?: IoK8SApimachineryPkgApisMetaV14;
  /**
   * successPolicy specifies the policy when the Job can be declared as succeeded. If empty, the default behavior applies - the Job is declared as succeeded only when the number of succeeded pods equals to the completions. When the field is specified, it must be immutable and works only for the Indexed Jobs. Once the Job meets the SuccessPolicy, the lingering pods are terminated.
   *
   * This field is beta-level. To use this field, you must enable the `JobSuccessPolicy` feature gate (enabled by default).
   */
  successPolicy?: IoK8SApiBatchV18;
  /**
   * suspend specifies whether the Job controller should create Pods or not. If a Job is created with suspend set to true, no Pods are created by the Job controller. If a Job is suspended after creation (i.e. the flag goes from false to true), the Job controller will delete all active Pods associated with this Job. Users must design their workload to gracefully handle this. Suspending a Job will reset the StartTime field of the Job, effectively resetting the ActiveDeadlineSeconds timer too. Defaults to false.
   */
  suspend?: boolean;
  /**
   * Describes the pod that will be created when executing a job. The only allowed template.spec.restartPolicy values are "Never" or "OnFailure". More info: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/
   */
  template: IoK8SApiCoreV1175;
  /**
   * ttlSecondsAfterFinished limits the lifetime of a Job that has finished execution (either Complete or Failed). If this field is set, ttlSecondsAfterFinished after the Job finishes, it is eligible to be automatically deleted. When the Job is being deleted, its lifecycle guarantees (e.g. finalizers) will be honored. If this field is unset, the Job won't be automatically deleted. If this field is set to zero, the Job becomes eligible to be deleted immediately after it finishes.
   */
  ttlSecondsAfterFinished?: number;
  [k: string]: unknown;
}
/**
 * PodFailurePolicy describes how failed pods influence the backoffLimit.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.PodFailurePolicy".
 */
export interface IoK8SApiBatchV14 {
  /**
   * A list of pod failure policy rules. The rules are evaluated in order. Once a rule matches a Pod failure, the remaining of the rules are ignored. When no rule matches the Pod failure, the default handling applies - the counter of pod failures is incremented and it is checked against the backoffLimit. At most 20 elements are allowed.
   */
  rules: IoK8SApiBatchV15[];
  [k: string]: unknown;
}
/**
 * PodFailurePolicyRule describes how a pod failure is handled when the requirements are met. One of onExitCodes and onPodConditions, but not both, can be used in each rule.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.PodFailurePolicyRule".
 */
export interface IoK8SApiBatchV15 {
  /**
   * Specifies the action taken on a pod failure when the requirements are satisfied. Possible values are:
   *
   * - FailJob: indicates that the pod's job is marked as Failed and all
   *   running pods are terminated.
   * - FailIndex: indicates that the pod's index is marked as Failed and will
   *   not be restarted.
   *   This value is beta-level. It can be used when the
   *   `JobBackoffLimitPerIndex` feature gate is enabled (enabled by default).
   * - Ignore: indicates that the counter towards the .backoffLimit is not
   *   incremented and a replacement pod is created.
   * - Count: indicates that the pod is handled in the default way - the
   *   counter towards the .backoffLimit is incremented.
   * Additional values are considered to be added in the future. Clients should react to an unknown action by skipping the rule.
   *
   * Possible enum values:
   *  - `"Count"` This is an action which might be taken on a pod failure - the pod failure is handled in the default way - the counter towards .backoffLimit, represented by the job's .status.failed field, is incremented.
   *  - `"FailIndex"` This is an action which might be taken on a pod failure - mark the Job's index as failed to avoid restarts within this index. This action can only be used when backoffLimitPerIndex is set. This value is beta-level.
   *  - `"FailJob"` This is an action which might be taken on a pod failure - mark the pod's job as Failed and terminate all running pods.
   *  - `"Ignore"` This is an action which might be taken on a pod failure - the counter towards .backoffLimit, represented by the job's .status.failed field, is not incremented and a replacement pod is created.
   */
  action: "Count" | "FailIndex" | "FailJob" | "Ignore";
  /**
   * Represents the requirement on the container exit codes.
   */
  onExitCodes?: IoK8SApiBatchV16;
  /**
   * Represents the requirement on the pod conditions. The requirement is represented as a list of pod condition patterns. The requirement is satisfied if at least one pattern matches an actual pod condition. At most 20 elements are allowed.
   */
  onPodConditions?: IoK8SApiBatchV17[];
  [k: string]: unknown;
}
/**
 * PodFailurePolicyOnExitCodesRequirement describes the requirement for handling a failed pod based on its container exit codes. In particular, it lookups the .state.terminated.exitCode for each app container and init container status, represented by the .status.containerStatuses and .status.initContainerStatuses fields in the Pod status, respectively. Containers completed with success (exit code 0) are excluded from the requirement check.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.PodFailurePolicyOnExitCodesRequirement".
 */
export interface IoK8SApiBatchV16 {
  /**
   * Restricts the check for exit codes to the container with the specified name. When null, the rule applies to all containers. When specified, it should match one the container or initContainer names in the pod template.
   */
  containerName?: string;
  /**
   * Represents the relationship between the container exit code(s) and the specified values. Containers completed with success (exit code 0) are excluded from the requirement check. Possible values are:
   *
   * - In: the requirement is satisfied if at least one container exit code
   *   (might be multiple if there are multiple containers not restricted
   *   by the 'containerName' field) is in the set of specified values.
   * - NotIn: the requirement is satisfied if at least one container exit code
   *   (might be multiple if there are multiple containers not restricted
   *   by the 'containerName' field) is not in the set of specified values.
   * Additional values are considered to be added in the future. Clients should react to an unknown operator by assuming the requirement is not satisfied.
   *
   * Possible enum values:
   *  - `"In"`
   *  - `"NotIn"`
   */
  operator: "In" | "NotIn";
  /**
   * Specifies the set of values. Each returned container exit code (might be multiple in case of multiple containers) is checked against this set of values with respect to the operator. The list of values must be ordered and must not contain duplicates. Value '0' cannot be used for the In operator. At least one element is required. At most 255 elements are allowed.
   */
  values: number[];
  [k: string]: unknown;
}
/**
 * PodFailurePolicyOnPodConditionsPattern describes a pattern for matching an actual pod condition type.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.PodFailurePolicyOnPodConditionsPattern".
 */
export interface IoK8SApiBatchV17 {
  /**
   * Specifies the required Pod condition status. To match a pod condition it is required that the specified status equals the pod condition status. Defaults to True.
   */
  status: string;
  /**
   * Specifies the required Pod condition type. To match a pod condition it is required that specified type equals the pod condition type.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * SuccessPolicy describes when a Job can be declared as succeeded based on the success of some indexes.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.SuccessPolicy".
 */
export interface IoK8SApiBatchV18 {
  /**
   * rules represents the list of alternative rules for the declaring the Jobs as successful before `.status.succeeded >= .spec.completions`. Once any of the rules are met, the "SucceededCriteriaMet" condition is added, and the lingering pods are removed. The terminal state for such a Job has the "Complete" condition. Additionally, these rules are evaluated in order; Once the Job meets one of the rules, other rules are ignored. At most 20 elements are allowed.
   */
  rules: IoK8SApiBatchV19[];
  [k: string]: unknown;
}
/**
 * SuccessPolicyRule describes rule for declaring a Job as succeeded. Each rule must have at least one of the "succeededIndexes" or "succeededCount" specified.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.SuccessPolicyRule".
 */
export interface IoK8SApiBatchV19 {
  /**
   * succeededCount specifies the minimal required size of the actual set of the succeeded indexes for the Job. When succeededCount is used along with succeededIndexes, the check is constrained only to the set of indexes specified by succeededIndexes. For example, given that succeededIndexes is "1-4", succeededCount is "3", and completed indexes are "1", "3", and "5", the Job isn't declared as succeeded because only "1" and "3" indexes are considered in that rules. When this field is null, this doesn't default to any value and is never evaluated at any time. When specified it needs to be a positive integer.
   */
  succeededCount?: number;
  /**
   * succeededIndexes specifies the set of indexes which need to be contained in the actual set of the succeeded indexes for the Job. The list of indexes must be within 0 to ".spec.completions-1" and must not contain duplicates. At least one element is required. The indexes are represented as intervals separated by commas. The intervals can be a decimal integer or a pair of decimal integers separated by a hyphen. The number are listed in represented by the first and last element of the series, separated by a hyphen. For example, if the completed indexes are 1, 3, 4, 5 and 7, they are represented as "1,3-5,7". When this field is null, this field doesn't default to any value and is never evaluated at any time.
   */
  succeededIndexes?: string;
  [k: string]: unknown;
}
/**
 * CronJobStatus represents the current state of a cron job.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.CronJobStatus".
 */
export interface IoK8SApiBatchV110 {
  /**
   * A list of pointers to currently running jobs.
   */
  active?: IoK8SApiCoreV116[];
  /**
   * Information when was the last time the job was successfully scheduled.
   */
  lastScheduleTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Information when was the last time the job successfully completed.
   */
  lastSuccessfulTime?: IoK8SApimachineryPkgApisMetaV1Time;
  [k: string]: unknown;
}
/**
 * CronJobList is a collection of cron jobs.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.CronJobList".
 */
export interface IoK8SApiBatchV1CronJobList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * items is the list of CronJobs.
   */
  items: IoK8SApiBatchV1[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}
/**
 * Job represents the configuration of a single job.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.Job".
 */
export interface IoK8SApiBatchV111 {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard object's metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV1;
  /**
   * Specification of the desired behavior of a job. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  spec?: IoK8SApiBatchV13;
  /**
   * Current status of a job. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#spec-and-status
   */
  status?: IoK8SApiBatchV112;
  [k: string]: unknown;
}
/**
 * JobStatus represents the current state of a Job.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.JobStatus".
 */
export interface IoK8SApiBatchV112 {
  /**
   * The number of pending and running pods which are not terminating (without a deletionTimestamp). The value is zero for finished jobs.
   */
  active?: number;
  /**
   * completedIndexes holds the completed indexes when .spec.completionMode = "Indexed" in a text format. The indexes are represented as decimal integers separated by commas. The numbers are listed in increasing order. Three or more consecutive numbers are compressed and represented by the first and last element of the series, separated by a hyphen. For example, if the completed indexes are 1, 3, 4, 5 and 7, they are represented as "1,3-5,7".
   */
  completedIndexes?: string;
  /**
   * Represents time when the job was completed. It is not guaranteed to be set in happens-before order across separate operations. It is represented in RFC3339 form and is in UTC. The completion time is set when the job finishes successfully, and only then. The value cannot be updated or removed. The value indicates the same or later point in time as the startTime field.
   */
  completionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * The latest available observations of an object's current state. When a Job fails, one of the conditions will have type "Failed" and status true. When a Job is suspended, one of the conditions will have type "Suspended" and status true; when the Job is resumed, the status of this condition will become false. When a Job is completed, one of the conditions will have type "Complete" and status true.
   *
   * A job is considered finished when it is in a terminal condition, either "Complete" or "Failed". A Job cannot have both the "Complete" and "Failed" conditions. Additionally, it cannot be in the "Complete" and "FailureTarget" conditions. The "Complete", "Failed" and "FailureTarget" conditions cannot be disabled.
   *
   * More info: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/
   */
  conditions?: IoK8SApiBatchV113[];
  /**
   * The number of pods which reached phase Failed. The value increases monotonically.
   */
  failed?: number;
  /**
   * FailedIndexes holds the failed indexes when spec.backoffLimitPerIndex is set. The indexes are represented in the text format analogous as for the `completedIndexes` field, ie. they are kept as decimal integers separated by commas. The numbers are listed in increasing order. Three or more consecutive numbers are compressed and represented by the first and last element of the series, separated by a hyphen. For example, if the failed indexes are 1, 3, 4, 5 and 7, they are represented as "1,3-5,7". The set of failed indexes cannot overlap with the set of completed indexes.
   *
   * This field is beta-level. It can be used when the `JobBackoffLimitPerIndex` feature gate is enabled (enabled by default).
   */
  failedIndexes?: string;
  /**
   * The number of active pods which have a Ready condition and are not terminating (without a deletionTimestamp).
   */
  ready?: number;
  /**
   * Represents time when the job controller started processing a job. When a Job is created in the suspended state, this field is not set until the first time it is resumed. This field is reset every time a Job is resumed from suspension. It is represented in RFC3339 form and is in UTC.
   *
   * Once set, the field can only be removed when the job is suspended. The field cannot be modified while the job is unsuspended or finished.
   */
  startTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * The number of pods which reached phase Succeeded. The value increases monotonically for a given spec. However, it may decrease in reaction to scale down of elastic indexed jobs.
   */
  succeeded?: number;
  /**
   * The number of pods which are terminating (in phase Pending or Running and have a deletionTimestamp).
   *
   * This field is beta-level. The job controller populates the field when the feature gate JobPodReplacementPolicy is enabled (enabled by default).
   */
  terminating?: number;
  /**
   * uncountedTerminatedPods holds the UIDs of Pods that have terminated but the job controller hasn't yet accounted for in the status counters.
   *
   * The job controller creates pods with a finalizer. When a pod terminates (succeeded or failed), the controller does three steps to account for it in the job status:
   *
   * 1. Add the pod UID to the arrays in this field. 2. Remove the pod finalizer. 3. Remove the pod UID from the arrays while increasing the corresponding
   *     counter.
   *
   * Old jobs might not be tracked using this field, in which case the field remains null. The structure is empty for finished jobs.
   */
  uncountedTerminatedPods?: IoK8SApiBatchV114;
  [k: string]: unknown;
}
/**
 * JobCondition describes current state of a job.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.JobCondition".
 */
export interface IoK8SApiBatchV113 {
  /**
   * Last time the condition was checked.
   */
  lastProbeTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Last time the condition transit from one status to another.
   */
  lastTransitionTime?: IoK8SApimachineryPkgApisMetaV1Time;
  /**
   * Human readable message indicating details about last transition.
   */
  message?: string;
  /**
   * (brief) reason for the condition's last transition.
   */
  reason?: string;
  /**
   * Status of the condition, one of True, False, Unknown.
   */
  status: string;
  /**
   * Type of job condition, Complete or Failed.
   */
  type: string;
  [k: string]: unknown;
}
/**
 * UncountedTerminatedPods holds UIDs of Pods that have terminated but haven't been accounted in Job status counters.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.UncountedTerminatedPods".
 */
export interface IoK8SApiBatchV114 {
  /**
   * failed holds UIDs of failed Pods.
   */
  failed?: string[];
  /**
   * succeeded holds UIDs of succeeded Pods.
   */
  succeeded?: string[];
  [k: string]: unknown;
}
/**
 * JobList is a collection of jobs.
 *
 * This interface was referenced by `K8STypes`'s JSON-Schema
 * via the `definition` "io.k8s.api.batch.v1.JobList".
 */
export interface IoK8SApiBatchV1JobList {
  /**
   * APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources
   */
  apiVersion?: string;
  /**
   * items is the list of Jobs.
   */
  items: IoK8SApiBatchV111[];
  /**
   * Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds
   */
  kind?: string;
  /**
   * Standard list metadata. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
   */
  metadata?: IoK8SApimachineryPkgApisMetaV16;
  [k: string]: unknown;
}


// Clean type aliases for main resources
export type Pod = IoK8SApiCoreV1149;
export type Service = IoK8SApiCoreV1186;
export type ConfigMap = IoK8SApiCoreV130;
export type Deployment = IoK8SApiAppsV17;
export type ReplicaSet = IoK8SApiAppsV113;
export type StatefulSet = IoK8SApiAppsV118;
export type Job = IoK8SApiBatchV111;
export type CronJob = IoK8SApiBatchV1;

// Clean type aliases for commonly used types
export type Container = IoK8SApiCoreV137;
export type Volume = IoK8SApiCoreV1161;
export type VolumeMount = IoK8SApiCoreV163;
export type EnvVar = IoK8SApiCoreV138;
export type PersistentVolumeClaim = IoK8SApiCoreV1145;
export type ResourceRequirements = IoK8SApiCoreV156;
export type PodSpec = IoK8SApiCoreV1150;
export type ObjectMeta = IoK8SApimachineryPkgApisMetaV1;
