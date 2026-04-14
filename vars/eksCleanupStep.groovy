/**
 * Shared EKS cleanup step for Jenkins pipeline post blocks.
 *
 * Cleans up EKS resources that CloudFormation cannot delete on its own:
 * - Kubernetes namespace (waits for ENI drain)
 * - EKS-managed instance profiles (detaches roles so CFN can delete them)
 * - Orphaned EKS security groups (blocks VPC deletion)
 *
 * Usage:
 *   eksCleanupStep(stackName: 'my-cfn-stack', eksClusterName: 'my-cluster', kubeContext: 'my-context')
 */
def call(Map config = [:]) {
    def stackName = config.stackName
    def eksClusterName = config.eksClusterName
    def kubeContext = config.kubeContext ?: eksClusterName

    // 1. Wait for namespace to fully terminate so Kubernetes-managed ENIs are
    //    cleaned up before CloudFormation tries to delete the VPC/EKS cluster.
    sh """
        if kubectl --context=${kubeContext} get namespace ma >/dev/null 2>&1; then
            echo "CLEANUP: Deleting namespace ma"
            kubectl --context=${kubeContext} delete namespace ma --ignore-not-found --timeout=60s || true
            kubectl --context=${kubeContext} wait --for=delete namespace/ma --timeout=300s || true
        fi
    """

    // 2. Remove EKS-managed instance profiles that prevent IAM role deletion.
    //    EKS auto-creates instance profiles and attaches the node pool role,
    //    but CFN doesn't know about them, so role deletion fails.
    sh """
        echo "CLEANUP: Detaching IAM roles from EKS-managed instance profiles"
        stack_roles=\$(aws cloudformation describe-stack-resources \
            --stack-name "${stackName}" \
            --query "StackResources[?ResourceType=='AWS::IAM::Role'].PhysicalResourceId" \
            --output text 2>/dev/null || echo "")
        for role in \$stack_roles; do
            for ip in \$(aws iam list-instance-profiles-for-role --role-name "\$role" \
                --query 'InstanceProfiles[*].InstanceProfileName' --output text 2>/dev/null); do
                echo "CLEANUP: Removing role \$role from instance profile \$ip"
                aws iam remove-role-from-instance-profile \
                    --instance-profile-name "\$ip" --role-name "\$role" 2>/dev/null || true
                echo "CLEANUP: Deleting instance profile \$ip"
                aws iam delete-instance-profile --instance-profile-name "\$ip" 2>/dev/null || true
            done
        done
    """

    // 3. Clean up orphaned EKS security groups that block VPC deletion.
    sh """
        echo "CLEANUP: Finding orphaned EKS security groups for cluster ${eksClusterName}"
        eks_sgs=\$(aws ec2 describe-security-groups \
            --filters "Name=tag:aws:eks:cluster-name,Values=${eksClusterName}" \
            --query 'SecurityGroups[*].GroupId' --output text 2>/dev/null || echo "")
        if [ -z "\$eks_sgs" ]; then
            echo "CLEANUP: No orphaned EKS security groups found"
        else
            for sg in \$eks_sgs; do
                echo "CLEANUP: Deleting EKS security group \$sg"
                for i in 1 2 3 4 5; do
                    if aws ec2 delete-security-group --group-id "\$sg" >/dev/null 2>&1; then
                        echo "CLEANUP: Deleted SG \$sg"
                        break
                    fi
                    echo "CLEANUP: SG \$sg delete failed (attempt \$i), waiting for ENIs to drain..."
                    sleep 30
                done
            done
        fi
    """
}
