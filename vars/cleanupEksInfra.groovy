/**
 * cleanupEksInfra — AWS-level infrastructure teardown for EKS.
 *
 * Cleans up resources CloudFormation can't delete on its own:
 * EKS-managed instance profiles, orphaned EKS security groups, and
 * a namespace-terminated wait to let ENIs detach. Call this AFTER
 * teardownMaApp and BEFORE CFN delete-stack. EKS-only.
 *
 * Usage:
 *   cleanupEksInfra(stackName: 'my-cfn-stack', eksClusterName: 'my-cluster', kubeContext: 'my-context')
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
    //    Revoke ingress/egress rules first (breaks circular SG refs so ENIs
    //    can detach), then retry delete 10 x 60s. On failure, dump residual
    //    ENI/rule state.
    sh """
        echo "CLEANUP: Finding orphaned EKS security groups for cluster ${eksClusterName}"
        eks_sgs=\$(aws ec2 describe-security-groups \
            --filters "Name=tag:aws:eks:cluster-name,Values=${eksClusterName}" \
            --query 'SecurityGroups[*].GroupId' --output text 2>/dev/null || echo "")
        if [ -z "\$eks_sgs" ]; then
            echo "CLEANUP: No orphaned EKS security groups found"
        else
            # (a) Revoke rules — one describe per SG, split ingress/egress via jq.
            for sg in \$eks_sgs; do
                echo "CLEANUP: Revoking ingress/egress rules on SG \$sg to release ENI attachments"
                sg_json=\$(aws ec2 describe-security-groups --group-ids "\$sg" \
                    --query 'SecurityGroups[0]' --output json 2>/dev/null || echo '{}')
                ingress=\$(echo "\$sg_json" | jq -c '.IpPermissions // []')
                egress=\$(echo "\$sg_json" | jq -c '.IpPermissionsEgress // []')
                if [ "\$ingress" != '[]' ]; then
                    aws ec2 revoke-security-group-ingress --group-id "\$sg" \
                        --ip-permissions "\$ingress" >/dev/null 2>&1 || true
                fi
                if [ "\$egress" != '[]' ]; then
                    aws ec2 revoke-security-group-egress --group-id "\$sg" \
                        --ip-permissions "\$egress" >/dev/null 2>&1 || true
                fi
            done

            # (b) Retry delete up to 10 min.
            for sg in \$eks_sgs; do
                echo "CLEANUP: Deleting EKS security group \$sg"
                deleted=false
                for i in 1 2 3 4 5 6 7 8 9 10; do
                    if aws ec2 delete-security-group --group-id "\$sg" >/dev/null 2>&1; then
                        echo "CLEANUP: Deleted SG \$sg on attempt \$i"
                        deleted=true
                        break
                    fi
                    echo "CLEANUP: SG \$sg delete failed (attempt \$i/10), waiting 60s for ENIs to drain..."
                    sleep 60
                done
                if [ "\$deleted" = "false" ]; then
                    # (c) Residual-state dump — exactly what ENIs/rules held the SG alive.
                    echo "CLEANUP: SG \$sg still present after 10 retries — residual ENI/rule state:"
                    aws ec2 describe-network-interfaces --filters "Name=group-id,Values=\$sg" \
                        --query 'NetworkInterfaces[].[NetworkInterfaceId,Status,Attachment.InstanceId,Description]' \
                        --output table 2>/dev/null || true
                    aws ec2 describe-security-groups --group-ids "\$sg" \
                        --query 'SecurityGroups[0].[IpPermissions,IpPermissionsEgress]' \
                        --output json 2>/dev/null || true
                fi
            done
        fi
    """
}
