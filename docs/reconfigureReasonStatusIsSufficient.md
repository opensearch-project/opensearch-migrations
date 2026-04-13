That is a fantastic catch. You’ve zeroed in on one of the most common misconceptions in the Kubernetes Custom Resource/CEL ecosystem.

**No, you do not need to duplicate the values into annotations.** The original document was operating on a partial misunderstanding of how the Kubernetes API handles subresources.

Here is the exact technical reality of why you can drop the annotation duplication and rely purely on `.status`.

### The Misconception vs. The Reality

The original document states:

> *"CEL cannot see `status` during a standard `UPDATE` when the status subresource is enabled."*

**Why the author thought this:**
When a CRD has the `/status` subresource enabled, standard `PUT`/`PATCH` requests to the main resource endpoint strictly ignore any changes a user tries to make to the `.status` field. Because of this, you cannot write a CEL policy on the main resource to validate a *transition* in status (e.g., "Don't let the user change the status to Ready"). If you try, the policy simply won't see the incoming status change because the API server drops it before admission control.

**The Reality for Your Use Case:**
You aren't trying to validate a *change* to the status. You are trying to read the *existing* status to decide if you should allow a change to the `.spec` (the Lock-on-Complete pattern).

When Argo attempts to update the `.spec`, the Kubernetes API server fetches the current state of the resource from `etcd` and passes it to your CEL policy as `oldObject`. **This `oldObject` contains the complete resource, including the fully populated `.status` field.**

### How This Massively Simplifies Your Design

Because `oldObject.status` is fully visible during a `spec` update, you can completely eliminate the `migrations.opensearch.org/phase` annotation. You don't have to worry about the atomicity of patching an annotation and a status separately, and you don't have to worry about them drifting out of sync.

The Single Source of Truth becomes exactly what it should be natively: `status.phase`.

#### The Simplified CEL Policy

Your Lock-on-Complete pattern for terminal resources like `DataSnapshot` can just directly reference the status using the `has()` macro (to ensure it doesn't crash if the status hasn't been initialized yet):

```yaml
validations:
  # Lock-on-Complete: Freeze spec for finished work products
  - expression: |
      !has(oldObject.status) ||
      !has(oldObject.status.phase) ||
      oldObject.status.phase != 'Completed' ||
      (object.spec == oldObject.spec)
    message: "Consistency Guard: This resource is 'Completed' and its specification is permanently sealed."

```

### The Argo Workflow Implication

This makes your Phase 3 ("Mark Done") incredibly clean.

Instead of Argo executing a two-step patch:

1. ~~`kubectl patch ... --type=merge -p '{"metadata":{"annotations":{"phase":"Completed"}}}'`~~ *(Delete this)*
2. `kubectl patch ... --subresource=status --type=merge -p '{"status":{"phase":"Completed"}}'`

You simply execute the status patch. The next time Argo tries to run an update against the main resource, the VAP will read that completed status from `oldObject` and throw the `403` if the specs don't match.

**Would you like me to do a final sweep of the design document and strip out all references to the `migrations.opensearch.org/phase` annotation so you have a perfectly clean, native-K8s version to hand off?**