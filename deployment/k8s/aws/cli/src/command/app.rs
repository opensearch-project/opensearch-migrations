//! The deploy orchestrator — the `manual_path` pipeline as a state machine.
//!
//! Each phase (CFN deploy, kubeconfig, image mirror, helm install) advances
//! `last_step` in [`State`] so a resumed run picks up where it left off. The
//! module is generic over a [`CommandRunner`], so the whole pipeline is
//! asserted against a [`MockRunner`](crate::runner::MockRunner) without AWS.
//!
//! This module deliberately contains NO terminal rendering or prompting — the
//! interactive surface lives in [`crate::tui`] and the dispatcher in
//! [`crate::cli`]. Here it's pure orchestration + state transitions.

use crate::artifact;
use crate::cfn;
use crate::config::Env;
use crate::ecr as ecr_sdk;
use crate::error::{Error, Result};
use crate::helm;
use crate::oci::{self, RegistryCred};
use crate::runner::CommandRunner;
use crate::state::State;
use crate::{cfn::TemplateVariant, ui};

/// The orchestrator context: resolved environment, the runner, and the live
/// state for the active stage.
pub struct App<'r, R: CommandRunner> {
    pub env: Env,
    pub runner: &'r R,
    pub state: State,
}

impl<'r, R: CommandRunner> App<'r, R> {
    /// Build an app context and load the stage's state from disk.
    pub fn load(env: Env, runner: &'r R) -> Result<Self> {
        let mut state = State::new(env.stage_dir());
        state.load()?;
        Ok(Self { env, runner, state })
    }

    /// CFN deploy or skip. Idempotent: a healthy stack or `SKIP_CFN_DEPLOY=Y`
    /// short-circuits. On a real deploy it runs `aws cloudformation deploy` and
    /// advances `last_step` to `cfn_done`. `template_file` is the already-
    /// fetched template path.
    pub fn cfn_deploy_or_skip(&mut self, template_file: &str) -> Result<()> {
        let stage_name = self.state.get_owned("STAGE_NAME", "ma");
        let stack_name = self.state.get_owned(
            "CFN_STACK_NAME",
            &format!("MigrationAssistant-{stage_name}"),
        );
        self.state.set("CFN_STACK_NAME", &stack_name);
        let region = self.state.get_owned("AWS_REGION", "");
        if region.is_empty() {
            return Err(Error::die("AWS_REGION not set"));
        }

        if self.state.get_or("SKIP_CFN_DEPLOY", "N") == "Y" {
            ui::info("--skip-cfn-deploy → skipping CFN entirely");
            return Ok(());
        }
        if self.cfn_stack_healthy(&stack_name, &region) {
            ui::ok(&format!(
                "CFN stack {stack_name} already healthy; skipping deploy"
            ));
            self.state.set("last_step", "cfn_done");
            self.state.save()?;
            return Ok(());
        }

        // Build parameter overrides for the chosen variant.
        let variant = TemplateVariant::from_state(
            &self.state.get_owned("CFN_TEMPLATE_VARIANT", "create-vpc"),
        )
        .ok_or_else(|| Error::die("unknown CFN_TEMPLATE_VARIANT"))?;
        let mut params = vec![format!("Stage={stage_name}")];
        if variant == TemplateVariant::ImportVpc {
            let vpc = self.state.get_owned("IMPORT_VPC_ID", "");
            let subnets = self.state.get_owned("IMPORT_SUBNET_IDS", "");
            if vpc.is_empty() {
                return Err(Error::die("--deploy-import-vpc-cfn requires --vpc-id"));
            }
            if subnets.is_empty() {
                return Err(Error::die("--deploy-import-vpc-cfn requires --subnet-ids"));
            }
            params.push(format!("VPCId={vpc}"));
            params.push(format!("VPCSubnetIds={subnets}"));
            self.append_endpoint_params(&mut params);
        }

        ui::step(&format!(
            "Deploying CFN stack: {stack_name} (region={region})"
        ));
        // Run `aws cloudformation deploy` with a background describe-stack-events
        // tailer (via bash) so the operator sees live resource progress — the
        // deploy command itself is silent for the whole ~15-20 min create.
        let script = artifact::cfn_deploy_script(&region, &stack_name, template_file, &params);
        let out = self
            .runner
            .run_streamed("bash", &["-c", &script], &[], "cfn");
        if !out.success() {
            return Err(Error::die(format!("CFN deploy failed (rc={})", out.status)));
        }
        ui::ok(&format!("CFN stack {stack_name} deployed"));
        self.state.set("last_step", "cfn_done");
        self.state.save()?;
        Ok(())
    }

    fn cfn_stack_healthy(&self, stack: &str, region: &str) -> bool {
        let out = self.runner.run(
            "aws",
            &[
                "cloudformation",
                "describe-stacks",
                "--region",
                region,
                "--stack-name",
                stack,
                "--query",
                "Stacks[0].StackStatus",
                "--output",
                "text",
            ],
        );
        matches!(out.trimmed_stdout(), "CREATE_COMPLETE" | "UPDATE_COMPLETE")
    }

    /// Translate `CREATE_VPC_ENDPOINTS` tokens into `Create*Endpoint=true`
    /// params, resolving route-table IDs for the S3 gateway endpoint.
    fn append_endpoint_params(&self, params: &mut Vec<String>) {
        let list = self.state.get_owned("CREATE_VPC_ENDPOINTS", "");
        if list.is_empty() {
            return;
        }
        let region = self.state.get_owned("AWS_REGION", "");
        let subnets = self.state.get_owned("IMPORT_SUBNET_IDS", "");
        let vpc = self.state.get_owned("IMPORT_VPC_ID", "");
        for token in crate::util::split_csv(&list) {
            match cfn::endpoint_param(&token) {
                cfn::EndpointParam::Param(p) => params.push(p.to_string()),
                cfn::EndpointParam::S3Gateway => {
                    params.push("CreateS3Endpoint=true".to_string());
                    let rt_ids = self.subnet_route_table_ids(&vpc, &subnets, &region);
                    if !rt_ids.is_empty() {
                        params.push(format!("S3EndpointRouteTableIds={rt_ids}"));
                    } else {
                        ui::warn("no route-table IDs resolved for S3 gateway endpoint");
                    }
                }
                cfn::EndpointParam::Unknown => {
                    if !token.is_empty() {
                        ui::warn(&format!(
                            "ignoring unknown --create-vpc-endpoints entry: '{token}'"
                        ));
                    }
                }
            }
        }
    }

    fn subnet_route_table_ids(&self, vpc: &str, subnets_csv: &str, region: &str) -> String {
        let mut out: Vec<String> = Vec::new();
        let mut main_rt = String::new();
        for sid in crate::util::split_csv(subnets_csv) {
            if sid.is_empty() {
                continue;
            }
            let r = self.runner.run(
                "aws",
                &[
                    "ec2",
                    "describe-route-tables",
                    "--region",
                    region,
                    "--filters",
                    &format!("Name=association.subnet-id,Values={sid}"),
                    "--query",
                    "RouteTables[0].RouteTableId",
                    "--output",
                    "text",
                ],
            );
            let mut rt = r.trimmed_stdout().to_string();
            if rt.is_empty() || rt == "None" {
                if main_rt.is_empty() {
                    let m = self.runner.run(
                        "aws",
                        &[
                            "ec2",
                            "describe-route-tables",
                            "--region",
                            region,
                            "--filters",
                            &format!("Name=vpc-id,Values={vpc}"),
                            "Name=association.main,Values=true",
                            "--query",
                            "RouteTables[0].RouteTableId",
                            "--output",
                            "text",
                        ],
                    );
                    main_rt = m.trimmed_stdout().to_string();
                }
                rt = main_rt.clone();
            }
            if !rt.is_empty() && rt != "None" && !out.contains(&rt) {
                out.push(rt);
            }
        }
        out.join(",")
    }

    /// Read the EKS cluster name from CFN outputs and `aws eks update-kubeconfig`
    /// it, binding the kube context. Advances no `last_step` (it's a setup step
    /// shared by build/crane/helm).
    pub fn kubeconfig_setup(&mut self) -> Result<String> {
        let stack = self.state.get_owned("CFN_STACK_NAME", "");
        let region = self.state.get_owned("AWS_REGION", "");
        let outputs = self.cfn_outputs(&stack, &region);
        let cluster = cfn::pick(&outputs, &["MIGRATIONS_EKS_CLUSTER_NAME", "EKSClusterName"])
            .ok_or_else(|| {
                Error::die(format!(
                    "could not read EKS cluster name from CFN outputs of '{stack}'"
                ))
            })?
            .to_string();
        if let Some(reg) = cfn::pick(&outputs, &["MIGRATIONS_ECR_REGISTRY", "ECRRegistry"]) {
            self.state.set("CRANE_REGISTRY", reg);
        }
        let kube_ctx = self.state.get_owned("KUBECTL_CONTEXT", &cluster);
        self.runner.run(
            "aws",
            &[
                "eks",
                "update-kubeconfig",
                "--region",
                &region,
                "--name",
                &cluster,
                "--alias",
                &kube_ctx,
            ],
        );
        if self.state.get_or("SKIP_KUBECONFIG_UPDATE", "N") != "Y" {
            self.runner
                .run("kubectl", &["config", "use-context", &kube_ctx]);
        }
        self.state.set("EKS_CLUSTER", &cluster);
        self.state.set("KUBECTL_CONTEXT", &kube_ctx);
        self.state.save()?;
        Ok(kube_ctx)
    }

    /// Read + parse CFN stack outputs into flat KEY=VALUE lines —
    /// `cfn_outputs`, but routed through the runner.
    pub fn cfn_outputs(&self, stack: &str, region: &str) -> Vec<String> {
        let out = self.runner.run(
            "aws",
            &[
                "cloudformation",
                "describe-stacks",
                "--region",
                region,
                "--stack-name",
                stack,
                "--query",
                "Stacks[0].Outputs[].[OutputKey,OutputValue]",
                "--output",
                "text",
            ],
        );
        cfn::parse_outputs(&out.stdout)
    }

    /// Mirror images to the private ECR, or skip when `MIRROR_IMAGES != Y` /
    /// already mirrored. Advances `last_step` to `crane_done` (or
    /// `crane_skipped`). `images` is the resolved image list (one ref per
    /// line).
    /// Obtain ECR credentials for `registry_host` via the AWS SDK.
    fn ecr_creds(&self, registry_host: &str, region: &str) -> Vec<RegistryCred> {
        match ecr_sdk::get_ecr_credentials(region) {
            Ok((user, pass)) => vec![RegistryCred {
                registry: registry_host.to_string(),
                username: user,
                password: pass,
            }],
            Err(e) => {
                ui::warn(&format!("ECR auth failed: {e}"));
                vec![]
            }
        }
    }

    /// Build the helm install/upgrade argument vector and run it, advancing
    /// `last_step` to `helm_done` on success.
    /// `helm_install_or_upgrade` (the watchers/diagnostics are side-channel and
    /// handled at the call site). `chart` is the resolved chart path;
    /// `values_files` are the extracted/written value files in apply order.
    pub fn helm_install_or_upgrade(&mut self, chart: &str, values_files: &[String]) -> Result<()> {
        let kube_ctx = self.state.get_owned("KUBECTL_CONTEXT", "");

        // Recovery: classify any stuck release before installing.
        let status = self.helm_release_status(&kube_ctx);
        if helm::classify_recovery(&status) == helm::Recovery::Uninstall {
            self.helm(
                &kube_ctx,
                &[
                    "uninstall",
                    helm::RELEASE_NAME,
                    "--namespace",
                    helm::NAMESPACE,
                    "--wait",
                ],
            );
        }

        self.ensure_namespace(&kube_ctx);
        let args = self.helm_install_args(chart, values_files)?;

        let mut helm_full: Vec<String> = Vec::new();
        if !kube_ctx.is_empty() {
            helm_full.push("--kube-context".into());
            helm_full.push(kube_ctx.clone());
        }
        helm_full.extend(args);

        let script = artifact::helm_install_script(&kube_ctx, helm::NAMESPACE, &helm_full);
        let out = self
            .runner
            .run_streamed("bash", &["-c", &script], &[], "helm");
        if !out.success() {
            return Err(Error::die(format!(
                "helm install/upgrade failed (rc={})",
                out.status
            )));
        }

        self.wait_for_console_pod(&kube_ctx)?;
        self.state.set("HELM_RELEASE", helm::RELEASE_NAME);
        self.state.set("last_step", "helm_done");
        self.state.save()?;
        Ok(())
    }

    fn ensure_namespace(&self, kube_ctx: &str) {
        if !self
            .runner
            .run(
                "kubectl",
                &kube_args(kube_ctx, &["get", "namespace", helm::NAMESPACE]),
            )
            .success()
        {
            self.runner.run(
                "kubectl",
                &kube_args(kube_ctx, &["create", "namespace", helm::NAMESPACE]),
            );
        }
    }

    fn helm_install_args(&self, chart: &str, values_files: &[String]) -> Result<Vec<String>> {
        let region = self.state.get_owned("AWS_REGION", "");
        let account = self.state.get_owned("AWS_ACCOUNT", "");
        let stage = self.state.get_owned("STAGE_NAME", "default");
        let ma_ver = self.state.get_owned("MA_VERSION", "");
        let stack = self.state.get_owned("CFN_STACK_NAME", "");
        let outputs = self.cfn_outputs(&stack, &region);
        let mirror = self.state.get_or("MIRROR_IMAGES", "Y") == "Y";
        let registry = self.state.get_owned("CRANE_REGISTRY", "");
        let mirror_tag = self.resolve_image_tag();

        let image_flags = if mirror && !registry.is_empty() {
            helm::mirrored_image_flags(&registry, &mirror_tag)
        } else {
            helm::public_image_flags(&ma_ver)
        };
        let snapshot_role = cfn::pick(&outputs, &["SNAPSHOT_ROLE", "SnapshotRole"]);
        let tls = helm::tls_flags(
            &self.state.get_owned("TLS_MODE", ""),
            &self.state.get_owned("PCA_ARN", ""),
            &region,
        )?;

        let mut args: Vec<String> = vec![
            "upgrade".into(),
            "--install".into(),
            helm::RELEASE_NAME.into(),
            chart.into(),
            "--namespace".into(),
            helm::NAMESPACE.into(),
        ];
        for vf in values_files {
            args.push("--values".into());
            args.push(vf.clone());
        }
        args.push("--set".into());
        args.push(format!("stageName={stage}"));
        args.push("--set".into());
        args.push(format!("aws.region={region}"));
        args.push("--set".into());
        args.push(format!("aws.account={account}"));
        if let Some(role) = snapshot_role {
            args.push("--set".into());
            args.push(format!("defaultBucketConfiguration.snapshotRoleArn={role}"));
        }
        if self.state.get_or("USE_GENERAL_NODE_POOL", "N") == "Y" {
            args.push("--set".into());
            args.push("cluster.useCustomKarpenterNodePool=false".into());
        }
        args.extend(tls);
        args.extend(image_flags);
        args.push("--timeout".into());
        args.push("25m".into());
        args.push("--wait".into());
        Ok(args)
    }

    fn wait_for_console_pod(&self, kube_ctx: &str) -> Result<()> {
        ui::step("Waiting for migration-console-0 to become Ready");
        let wait = self.runner.run_streamed(
            "kubectl",
            &kube_args(
                kube_ctx,
                &[
                    "wait",
                    "--namespace",
                    helm::NAMESPACE,
                    "--for=condition=ready",
                    "pod/migration-console-0",
                    "--timeout=10m",
                ],
            ),
            &[],
            "kubectl-wait",
        );
        if !wait.success() {
            dump_output(&wait);
            let pods = self.runner.run(
                "kubectl",
                &kube_args(kube_ctx, &["get", "pods", "--namespace", helm::NAMESPACE]),
            );
            dump_output(&pods);
            return Err(Error::die("migration-console-0 did not become Ready"));
        }
        ui::ok("migration-console-0 is Ready");
        Ok(())
    }

    fn helm_release_status(&self, kube_ctx: &str) -> String {
        let out = self.helm(
            kube_ctx,
            &[
                "status",
                helm::RELEASE_NAME,
                "--namespace",
                helm::NAMESPACE,
                "-o",
                "json",
            ],
        );
        if !out.success() {
            return String::new();
        }
        serde_json::from_str::<serde_json::Value>(&out.stdout)
            .ok()
            .and_then(|v| {
                v.get("info")
                    .and_then(|i| i.get("status"))
                    .and_then(|s| s.as_str())
                    .map(str::to_string)
            })
            .unwrap_or_default()
    }

    /// Run helm bound to the kube context.
    fn helm(&self, kube_ctx: &str, args: &[&str]) -> crate::runner::Output {
        let mut full = Vec::new();
        if !kube_ctx.is_empty() {
            full.push("--kube-context");
            full.push(kube_ctx);
        }
        full.extend_from_slice(args);
        self.runner.run("helm", &full)
    }

    // ----------------------------------------------------------------------
    // Headless deploy spine (CI / non-interactive).
    // ----------------------------------------------------------------------

    /// Whether this is a build-from-source run (`--build`).
    fn is_build(&self) -> bool {
        self.state.get_or("BUILD_FROM_SOURCE", "N") == "Y"
    }

    /// Resolve the CFN template path. On `--build` it Gradle-synthesizes the
    /// minified templates (CDK synth stays in Gradle — not reimplemented) and
    /// returns the local path; otherwise it downloads the release template to a
    /// temp file.
    pub fn resolve_cfn_template(&self) -> Result<String> {
        let variant = TemplateVariant::from_state(
            &self.state.get_owned("CFN_TEMPLATE_VARIANT", "create-vpc"),
        )
        .ok_or_else(|| Error::die("unknown CFN_TEMPLATE_VARIANT"))?;

        if self.is_build() {
            let base = self.base_dir()?;
            ui::step("Synthesizing CFN template (gradle cdkSynthMinified)");
            // STACK_NAME_SUFFIX="" → predictable template filenames. Stream so
            // the (multi-minute) gradle synth shows live progress.
            let out = self.runner.run_streamed(
                &format!("{base}/gradlew"),
                &artifact::cdk_synth_args(&base)
                    .iter()
                    .map(String::as_str)
                    .collect::<Vec<_>>(),
                &[("STACK_NAME_SUFFIX", "")],
                "cdk",
            );
            if !out.success() {
                return Err(Error::die(format!(
                    "cdkSynthMinified failed (rc={})",
                    out.status
                )));
            }
            let path = artifact::synthesized_template_path(&base, variant);
            if !std::path::Path::new(&path).is_file() {
                return Err(Error::die(format!(
                    "synthesized template not found: {path}"
                )));
            }
            Ok(path)
        } else {
            let ver = self.release_version()?;
            let repo = self.repo();
            let url = format!(
                "{}/{}",
                artifact::release_base_url(&repo, &ver),
                variant.template_name()
            );
            let dest = std::env::temp_dir()
                .join(format!("ma-template-{}.json", std::process::id()))
                .to_string_lossy()
                .to_string();
            ui::step(&format!("Downloading CFN template {ver}"));
            self.download_artifact(&url, &dest, "CFN template")?;
            Ok(dest)
        }
    }

    /// Grant the EKS access principal cluster-admin, if `--eks-access-principal-arn`
    /// was passed. Idempotent (skip-create if the entry exists, always associate
    /// the policy).
    pub fn grant_eks_access(&self) -> Result<()> {
        let arn = self.state.get_owned("EKS_ACCESS_PRINCIPAL_ARN", "");
        if arn.is_empty() {
            return Ok(());
        }
        let cluster = self.state.get_owned("EKS_CLUSTER", "");
        let region = self.state.get_owned("AWS_REGION", "");
        if cluster.is_empty() {
            return Err(Error::die("grant_eks_access: EKS_CLUSTER not resolved yet"));
        }
        ui::step(&format!("Configuring EKS access for {arn}"));
        let exists = self
            .runner
            .run(
                "aws",
                &[
                    "eks",
                    "describe-access-entry",
                    "--cluster-name",
                    &cluster,
                    "--principal-arn",
                    &arn,
                    "--region",
                    &region,
                ],
            )
            .success();
        if !exists {
            self.runner.run(
                "aws",
                &[
                    "eks",
                    "create-access-entry",
                    "--cluster-name",
                    &cluster,
                    "--principal-arn",
                    &arn,
                    "--type",
                    "STANDARD",
                    "--region",
                    &region,
                ],
            );
        }
        let out = self.runner.run(
            "aws",
            &[
                "eks",
                "associate-access-policy",
                "--cluster-name",
                &cluster,
                "--principal-arn",
                &arn,
                "--policy-arn",
                "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy",
                "--access-scope",
                "type=cluster",
                "--region",
                &region,
            ],
        );
        if !out.success() {
            return Err(Error::die(format!(
                "associate-access-policy failed (rc={})",
                out.status
            )));
        }
        Ok(())
    }

    /// Ensure the EKS Auto Mode `general-purpose` nodepool is enabled before the
    /// helm install, so the chart's `ma-dependency-installer` pre-install hook
    /// pods have warm capacity to schedule on. Without it the installer pods
    /// wait on a Karpenter cold-start that routinely blows the job's
    /// `activeDeadlineSeconds` (→ helm `DeadlineExceeded`). Runs unconditionally
    /// so that even `--build` deploys (whose buildkit `build-nodepool` is tainted
    /// and can't host installer pods) get capacity.
    /// Idempotent: a no-op when `general-purpose` is already present.
    pub fn ensure_general_node_pool(&self) -> Result<()> {
        let cluster = self.state.get_owned("EKS_CLUSTER", "");
        let region = self.state.get_owned("AWS_REGION", "");
        if cluster.is_empty() {
            return Ok(());
        }
        let current = self.runner.run(
            "aws",
            &[
                "eks",
                "describe-cluster",
                "--name",
                &cluster,
                "--region",
                &region,
                "--query",
                "cluster.computeConfig.nodePools",
                "--output",
                "text",
            ],
        );
        if current.trimmed_stdout().contains("general-purpose") {
            return Ok(());
        }
        ui::step("Enabling general-purpose nodepool for installer scheduling");
        let role = self.runner.run(
            "aws",
            &[
                "eks",
                "describe-cluster",
                "--name",
                &cluster,
                "--region",
                &region,
                "--query",
                "cluster.computeConfig.nodeRoleArn",
                "--output",
                "text",
            ],
        );
        let node_role = role.trimmed_stdout().to_string();
        if node_role.is_empty() || node_role == "None" {
            ui::warn("could not resolve nodeRoleArn; skipping general-purpose nodepool enable");
            return Ok(());
        }
        let compute = format!(
            "{{\"enabled\":true,\"nodePools\":[\"system\",\"general-purpose\"],\"nodeRoleArn\":\"{node_role}\"}}"
        );
        let up = self.runner.run_streamed(
            "aws",
            &[
                "eks",
                "update-cluster-config",
                "--name",
                &cluster,
                "--region",
                &region,
                "--compute-config",
                &compute,
                "--kubernetes-network-config",
                "{\"elasticLoadBalancing\":{\"enabled\":true}}",
                "--storage-config",
                "{\"blockStorage\":{\"enabled\":true}}",
            ],
            &[],
            "eks-nodepool",
        );
        if !up.success() {
            // Non-fatal: log and proceed — the install may still schedule.
            ui::warn(&format!(
                "update-cluster-config (general-purpose) failed (rc={}); proceeding",
                up.status
            ));
            return Ok(());
        }
        ui::step("Waiting for cluster update (general-purpose nodepool) to complete");
        self.runner.run_streamed(
            "aws",
            &[
                "eks",
                "wait",
                "cluster-active",
                "--name",
                &cluster,
                "--region",
                &region,
            ],
            &[],
            "eks-wait",
        );
        Ok(())
    }

    /// On `--build` without `--ma-images-source`, build + push every image to the
    /// private ECR registry via Gradle (`:buildImages:buildImagesToRegistry`).
    /// buildkit/ECR/multi-arch orchestration stays in Gradle. No-op otherwise.
    /// Build MA images via Gradle + buildkit.
    pub fn build_images_or_skip(&mut self) -> Result<()> {
        if !self.is_build() || !self.state.get_or("MA_IMAGES_SOURCE", "").is_empty() {
            return Ok(());
        }
        let base = self.base_dir()?;
        let region = self.state.get_owned("AWS_REGION", "");
        let kube_ctx = self.state.get_owned("KUBECTL_CONTEXT", "");
        let registry = {
            let stack = self.state.get_owned("CFN_STACK_NAME", "");
            let outputs = self.cfn_outputs(&stack, &region);
            cfn::pick(&outputs, &["MIGRATIONS_ECR_REGISTRY", "ECRRegistry"])
                .map(str::to_string)
                .unwrap_or_else(|| self.state.get_owned("CRANE_REGISTRY", ""))
        };
        if registry.is_empty() {
            return Err(Error::die("build_images: could not resolve ECR registry"));
        }
        let ecr_host = registry.split('/').next().unwrap_or(&registry).to_string();
        // The image tag MUST be identical for the gradle build (`-PimageVersion`)
        // and the helm `images.*.tag` flags, or the chart references an image
        // that was never pushed. Default to `latest` (gradle's own imageVersion
        // default + the reference's `image_tag="latest"`); never empty (an empty
        // tag yields `migrations_<name>_` → ErrImagePull → installer
        // DeadlineExceeded). Persist BUILD_IMAGE_TAG so helm_install reuses it.
        let image_tag = self.resolve_image_tag();
        self.state.set("BUILD_IMAGE_TAG", &image_tag);
        let builder = artifact::builder_name(&kube_ctx);
        let skip_test = self.state.get_or("SKIP_TEST_IMAGES", "N") == "Y";

        // Run the whole image-build block as a single bash script mirroring
        // Set up the kubernetes-driver buildkit builder (the
        // EKS agents have no local docker build daemon — buildkit runs in the
        // cluster), ECR-login, then gradlew :buildImages:buildImagesToRegistry
        // with one retry. buildkit/gradle orchestration stays in the proven
        // shell + Gradle; we don't reimplement it. Output is surfaced on
        // failure (the CLI captures subprocess output, so without this the
        // Jenkins console only shows our summary line).
        ui::step("Building images (buildkit setup + gradle buildImagesToRegistry)");
        let script = artifact::build_images_script(
            &base, &region, &ecr_host, &registry, &builder, &image_tag, &kube_ctx, skip_test,
        );
        // Stream: buildkit setup + multi-arch image build runs ~8+ min.
        let out = self
            .runner
            .run_streamed("bash", &["-c", &script], &[], "build");
        if !out.success() {
            // Output already streamed above; the summary is enough on failure.
            return Err(Error::die(format!(
                "buildImagesToRegistry failed (rc={})",
                out.status
            )));
        }
        // MA images are now in ECR. NOTE: do NOT mark crane "done" here — the
        // third-party images + charts still need mirroring (see
        // mirror_images_and_charts); `--build` only covers the MA images.
        self.state.set("BUILD_IMAGES_DONE", "Y");
        self.state.save()?;
        Ok(())
    }

    /// Mirror all third-party images + helm charts into the private ECR and
    /// generate a helm values override that repoints every sub-chart at the
    /// mirror. Returns the generated values file path (empty when mirroring is
    /// disabled via `--use-public-images`).
    ///
    /// Uses native oci-client for images and `helm pull/push` for charts.
    pub fn mirror_images_and_charts(&mut self) -> Result<Vec<String>> {
        if self.state.get_or("MIRROR_IMAGES", "Y") != "Y" {
            ui::info("MIRROR_IMAGES=N → using public images; skipping ECR mirror");
            return Ok(Vec::new());
        }
        let region = self.state.get_owned("AWS_REGION", "");
        let stack = self.state.get_owned("CFN_STACK_NAME", "");
        let outputs = self.cfn_outputs(&stack, &region);
        let registry = cfn::pick(&outputs, &["MIGRATIONS_ECR_REGISTRY", "ECRRegistry"])
            .map(str::to_string)
            .unwrap_or_else(|| self.state.get_owned("CRANE_REGISTRY", ""));
        if registry.is_empty() {
            return Err(Error::die("mirror: could not resolve ECR registry"));
        }
        let ecr_host = registry.split('/').next().unwrap_or(&registry).to_string();

        let creds = self.ecr_creds(&ecr_host, &region);
        if creds.is_empty() {
            return Err(Error::die("mirror: ECR auth failed"));
        }

        self.mirror_container_images(&ecr_host, &region, &creds)?;
        self.mirror_helm_charts(&ecr_host, &region)?;

        // Generate private-ECR values override.
        let values_out = std::env::temp_dir()
            .join(format!("ma-ecr-values-{}.yaml", std::process::id()))
            .to_string_lossy()
            .to_string();
        let yaml = crate::mirror::generate_private_ecr_values(&ecr_host);
        std::fs::write(&values_out, &yaml)
            .map_err(|e| Error::die(format!("failed to write ECR values file: {e}")))?;

        self.state.set("CRANE_REGISTRY", &registry);
        self.state.set("CRANE_MIRRORED", "1");
        self.state.save()?;
        Ok(vec![values_out])
    }

    fn mirror_container_images(
        &self,
        ecr_host: &str,
        region: &str,
        creds: &[RegistryCred],
    ) -> Result<()> {
        ui::step("Mirroring third-party container images to private ECR");
        let retry = OciRetry::from_env();
        let mut failed = 0usize;
        for &src in crate::mirror::IMAGES {
            let repo = crate::mirror::ecr_repo_name(src);
            let dst = crate::mirror::ecr_dest(src, ecr_host);
            if let Err(e) = ecr_sdk::create_repository(&repo, region) {
                ui::warn(&format!("create-repository {repo}: {e}"));
            }
            if retry.copy(src, &dst, creds) {
                ui::dim(&format!("  ✓ {src}"));
            } else {
                ui::err(&format!("  ✗ {src}"));
                failed += 1;
            }
        }
        if failed > 0 {
            return Err(Error::die(format!(
                "{failed} third-party images failed to mirror"
            )));
        }
        Ok(())
    }

    fn mirror_helm_charts(&self, ecr_host: &str, region: &str) -> Result<()> {
        ui::step("Mirroring helm charts to private ECR");
        match ecr_sdk::get_ecr_credentials(region) {
            Ok((_user, pass)) => {
                let login = self.runner.run(
                    "helm",
                    &[
                        "registry",
                        "login",
                        ecr_host,
                        "-u",
                        "AWS",
                        "--password-stdin",
                    ],
                );
                // helm registry login doesn't support --password-stdin via
                // runner (no stdin pipe), so write a temp script.
                if !login.success() {
                    let script =
                        format!("echo '{}' | helm registry login {ecr_host} -u AWS --password-stdin", pass.replace('\'', "'\\''"));
                    if !self.runner.run("bash", &["-c", &script]).success() {
                        ui::warn("helm ECR login failed — chart mirror may fail");
                    }
                }
            }
            Err(e) => ui::warn(&format!("ECR auth for helm: {e} — chart mirror may fail")),
        }
        let mut failed = 0usize;
        for chart in crate::mirror::CHARTS {
            if !self.mirror_one_chart(chart, ecr_host, region) {
                failed += 1;
            }
        }
        if failed > 0 {
            ui::warn(&format!("{failed} chart(s) failed to mirror"));
        }
        Ok(())
    }

    fn mirror_one_chart(
        &self,
        chart: &crate::mirror::ChartEntry,
        ecr_host: &str,
        region: &str,
    ) -> bool {
        let chart_repo = format!("charts/{}", chart.name);
        if let Err(e) = ecr_sdk::create_repository(&chart_repo, region) {
            ui::warn(&format!("create-repository {chart_repo}: {e}"));
        }
        let pull_ok = if chart.repo.starts_with("oci://") {
            self.runner
                .run(
                    "helm",
                    &[
                        "pull",
                        &format!("{}/{}", chart.repo, chart.name),
                        "--version",
                        chart.version,
                    ],
                )
                .success()
        } else {
            self.runner
                .run(
                    "helm",
                    &[
                        "pull",
                        chart.name,
                        "--repo",
                        chart.repo,
                        "--version",
                        chart.version,
                    ],
                )
                .success()
        };
        if !pull_ok {
            ui::err(&format!(
                "  ✗ chart pull failed: {} {}",
                chart.name, chart.version
            ));
            return false;
        }
        let tgz = format!("{}-{}.tgz", chart.name, chart.version);
        let push_ok = self
            .runner
            .run("helm", &["push", &tgz, &format!("oci://{ecr_host}/charts")])
            .success();
        let _ = std::fs::remove_file(&tgz);
        if push_ok {
            ui::dim(&format!("  ✓ {} {}", chart.name, chart.version));
        } else {
            ui::err(&format!("  ✗ chart push failed: {}", chart.name));
        }
        push_ok
    }

    /// Resolve the helm chart path + the values files to apply (in order).
    /// `--build`: the in-repo chart dir + its `values.yaml`/`valuesEks.yaml`.
    /// Release: download the `.tgz`, and extract its bundled `values.yaml` +
    /// `valuesEks.yaml` (helm can't read files inside an archive). A
    /// `--helm-values` extra file is appended last. Returns `(chart,
    /// values_files)`.
    pub fn resolve_chart(&self) -> Result<(String, Vec<String>)> {
        let extra = self.state.get_owned("HELM_EXTRA_VALUES_FILE", "");
        if self.is_build() {
            let base = self.base_dir()?;
            let chart = artifact::local_chart_dir(&base);
            let mut values = artifact::local_chart_values(&chart);
            if !extra.is_empty() {
                values.push(extra);
            }
            return Ok((chart, values));
        }
        let ver = self.release_version()?;
        let repo = self.repo();
        let name = artifact::chart_tarball_name(&ver);
        let url = format!("{}/{}", artifact::release_base_url(&repo, &ver), name);
        let tmp = std::env::temp_dir();
        let dest = tmp.join(&name).to_string_lossy().to_string();
        ui::step(&format!("Downloading helm chart {ver}"));
        self.download_artifact(&url, &dest, "helm chart")?;
        // Extract the bundled values files (helm can't reference paths inside a
        // tgz). The chart packs them under `migration-assistant/`.
        let tmp_str = tmp.to_string_lossy().to_string();
        self.runner.run(
            "tar",
            &[
                "xzf",
                &dest,
                "-C",
                &tmp_str,
                "migration-assistant/values.yaml",
                "migration-assistant/valuesEks.yaml",
            ],
        );
        let mut values = vec![
            tmp.join("migration-assistant/values.yaml")
                .to_string_lossy()
                .to_string(),
            tmp.join("migration-assistant/valuesEks.yaml")
                .to_string_lossy()
                .to_string(),
        ];
        if !extra.is_empty() {
            values.push(extra);
        }
        Ok((dest, values))
    }

    /// Download `url` to `dest`, streaming progress and failing FAST on a stall
    /// instead of hanging silently forever.
    ///
    /// The release lane previously used a bare `curl -fsSL -o dest url`: `-s`
    /// silences all output and there is no timeout, so if the GitHub →
    /// object-store (S3) redirect stalls, curl waits FOREVER with zero output —
    /// the console looks frozen on `Downloading …` (exactly the "stuck
    /// downloading helm chart" symptom). This hardens it with:
    ///   * `--connect-timeout`/`--max-time` — a stalled connection fails fast
    ///     with a clear error rather than hanging,
    ///   * `--retry` — ride out transient network blips, and
    ///   * streamed output via [`run_streamed`](crate::runner::CommandRunner::run_streamed)
    ///     plus `-#` (progress bar, no `-s`) so the operator SEES the download
    ///     move — or sees the error — live.
    fn download_artifact(&self, url: &str, dest: &str, what: &str) -> Result<()> {
        let out = self.runner.run_streamed(
            "curl",
            &[
                "-fL",
                "--connect-timeout",
                "30",
                "--max-time",
                "300",
                "--retry",
                "3",
                "--retry-delay",
                "2",
                "--retry-connrefused",
                "-#",
                "-o",
                dest,
                url,
            ],
            &[],
            "curl",
        );
        if !out.success() || !std::path::Path::new(dest).is_file() {
            return Err(Error::die(format!(
                "failed to download {what} from {url} (curl rc={}). \
                 Check network/proxy reachability — verify manually with: \
                 curl -fL -o /dev/null {url}",
                out.status
            )));
        }
        Ok(())
    }

    /// The repo root for `--build` (`--base-dir`), error if missing on a build run.
    fn base_dir(&self) -> Result<String> {
        let base = self.state.get_owned("BASE_DIR", "");
        if base.is_empty() {
            return Err(Error::die(
                "--build requires --base-dir <repo-root> to locate gradlew",
            ));
        }
        Ok(base)
    }

    /// The container image tag used for BOTH the gradle build and the helm
    /// `images.*.tag` flags — they MUST match or the chart pulls an image that
    /// was never pushed (an empty/mismatched tag → `migrations_<name>_…`
    /// ErrImagePull → the installer hook hangs → DeadlineExceeded).
    ///
    /// Resolution (first non-empty wins):
    ///   1. `IMAGE_TAG`        — explicit `--image-tag <x>` operator override
    ///      (use a git SHA / version here for immutable, reproducible deploys).
    ///   2. `BUILD_IMAGE_TAG`  — what the build step recorded it pushed.
    ///   3. `MA_VERSION`       — the `--version <v>` release lane: images are the
    ///      published `…:<version>`, so the version IS the tag. (Build lane only;
    ///      skipped under `--build`.)
    ///   4. `latest`           — the `--build` default. NOTE: today the
    ///      `:buildImages` gradle build hard-codes the pushed tag to `latest`
    ///      and ignores `-PimageVersion`, so on the source-build lane `latest`
    ///      is the ONLY tag that actually exists in ECR. Honoring a specific
    ///      `--image-tag` on `--build` additionally requires teaching the
    ///      buildImages gradle build to tag with that value — a build-side
    ///      change, not done here. Never returns empty.
    fn resolve_image_tag(&self) -> String {
        for key in ["IMAGE_TAG", "BUILD_IMAGE_TAG"] {
            let v = self.state.get_owned(key, "");
            if !v.is_empty() {
                return v;
            }
        }
        if !self.is_build() {
            let v = self.state.get_owned("MA_VERSION", "");
            if !v.is_empty() {
                return v;
            }
        }
        "latest".to_string()
    }

    /// The release version for the download lane: `local-build` under `--build`,
    /// else the explicit `--version` / saved `MA_VERSION`.
    fn release_version(&self) -> Result<String> {
        if self.is_build() {
            return Ok("local-build".to_string());
        }
        let v = self.state.get_owned("MA_VERSION", "");
        if v.is_empty() {
            return Err(Error::die(
                "no version to deploy: pass --version <ver> or --build",
            ));
        }
        Ok(v)
    }

    /// The GitHub repo slug for artifact URLs (overridable via `DEFAULT_REPO`).
    fn repo(&self) -> String {
        std::env::var("DEFAULT_REPO")
            .ok()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "opensearch-project/opensearch-migrations".to_string())
    }
}

/// Echo the last lines of a captured command's output to the console (stderr
/// then stdout, last 40 lines), so a failed deploy step is diagnosable from the
/// Jenkins log. The runner CAPTURES subprocess output rather than streaming it,
/// so without this a failure shows only our "rc=N" summary.
fn dump_output(out: &crate::runner::Output) {
    let lines: Vec<&str> = out
        .stderr
        .lines()
        .chain(out.stdout.lines())
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .take(40)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    for line in lines {
        if !line.trim().is_empty() {
            ui::dim(&format!("  | {line}"));
        }
    }
}

/// kubectl args bound to a context (prepended `--context X` when non-empty).
fn kube_args<'a>(kube_ctx: &'a str, args: &[&'a str]) -> Vec<&'a str> {
    let mut full = Vec::new();
    if !kube_ctx.is_empty() {
        full.push("--context");
        full.push(kube_ctx);
    }
    full.extend_from_slice(args);
    full
}

/// OCI image copy retry policy, read once from the environment.
/// `CRANE_RETRY_ATTEMPTS` (default 5) and `CRANE_RETRY_INITIAL_S` (default 5)
/// preserve backward-compat with the prior crane-based env-var names.
#[derive(Clone, Copy)]
struct OciRetry {
    attempts: u32,
    initial_secs: u64,
}

impl OciRetry {
    fn from_env() -> Self {
        fn env_or<T: std::str::FromStr>(key: &str, default: T) -> T {
            std::env::var(key)
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(default)
        }
        Self {
            attempts: env_or("CRANE_RETRY_ATTEMPTS", 5),
            initial_secs: env_or("CRANE_RETRY_INITIAL_S", 5),
        }
    }

    /// Copy `src` to `dst` with exponential backoff using the native OCI client.
    fn copy(self, src: &str, dst: &str, creds: &[RegistryCred]) -> bool {
        let mut backoff = self.initial_secs;
        for attempt in 1..=self.attempts {
            match oci::copy_image(src, dst, creds) {
                Ok(()) => return true,
                Err(e) => {
                    ui::warn(&format!("mirror attempt {attempt}/{}: {e}", self.attempts));
                    if attempt < self.attempts && backoff > 0 {
                        std::thread::sleep(std::time::Duration::from_secs(backoff));
                        backoff = backoff.saturating_mul(2);
                    }
                }
            }
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runner::MockRunner;

    fn env_in(dir: &std::path::Path) -> Env {
        Env {
            home: dir.to_path_buf(),
            stage: "default".into(),
            non_interactive: true,
            verbose: false,
            enable_agent: false,
            preview_ack: true,
        }
    }

    const REAL_OUTPUTS: &str = "MigrationsExportString\texport MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-default-us-east-1; export MIGRATIONS_ECR_REGISTRY=629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1; export SNAPSHOT_ROLE=arn:aws:iam::629003556176:role/snap";

    #[test]
    fn cfn_skips_when_flag_set() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("AWS_REGION", "us-east-1");
        app.state.set("SKIP_CFN_DEPLOY", "Y");
        app.cfn_deploy_or_skip("/tmp/template.json").unwrap();
        // No describe-stacks/deploy should have run.
        assert!(r.calls_to("aws").is_empty());
    }

    #[test]
    fn cfn_skips_when_stack_healthy() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new().stub("aws", &["describe-stacks"], 0, "CREATE_COMPLETE");
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("AWS_REGION", "us-east-1");
        app.state.set("STAGE_NAME", "ma");
        app.cfn_deploy_or_skip("/tmp/template.json").unwrap();
        assert_eq!(app.state.resumable_step(), "cfn_done");
        // describe-stacks ran, but deploy did NOT.
        assert!(r.any_call_contains("describe-stacks"));
        assert!(!r.any_call_contains("cloudformation deploy"));
    }

    #[test]
    fn cfn_runs_deploy_when_stack_absent() {
        let dir = tempfile::tempdir().unwrap();
        // Deploy now runs via `bash -c <script>` (the script wraps
        // `aws cloudformation deploy` + a background describe-stack-events tailer
        // so the long silent create shows live progress).
        let r = MockRunner::new()
            .stub("aws", &["describe-stacks"], 0, "DOES_NOT_EXIST")
            .stub("bash", &["cloudformation deploy"], 0, "");
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("AWS_REGION", "us-east-1");
        app.state.set("STAGE_NAME", "ma");
        app.cfn_deploy_or_skip("/tmp/template.json").unwrap();
        assert_eq!(app.state.resumable_step(), "cfn_done");
        let deploy = r
            .calls_to("bash")
            .into_iter()
            .find(|c| c.joined().contains("cloudformation deploy"))
            .unwrap()
            .joined();
        assert!(
            deploy.contains("--stack-name \"$STACK\"")
                && deploy.contains("STACK=\"MigrationAssistant-ma\"")
        );
        assert!(deploy.contains("CAPABILITY_NAMED_IAM"));
        assert!(deploy.contains("Stage=ma"));
        // The live-progress event tailer is wired in.
        assert!(deploy.contains("describe-stack-events"));
    }

    #[test]
    fn cfn_import_vpc_requires_vpc_id() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new().stub("aws", &["describe-stacks"], 0, "DOES_NOT_EXIST");
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("AWS_REGION", "us-east-1");
        app.state.set("CFN_TEMPLATE_VARIANT", "import-vpc");
        let err = app.cfn_deploy_or_skip("/tmp/t.json").unwrap_err();
        assert!(err.message.contains("--vpc-id"));
    }

    #[test]
    fn kubeconfig_reads_cluster_from_outputs() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new().stub("aws", &["describe-stacks"], 0, REAL_OUTPUTS);
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("AWS_REGION", "us-east-1");
        app.state
            .set("CFN_STACK_NAME", "MigrationAssistant-default");
        let ctx = app.kubeconfig_setup().unwrap();
        assert_eq!(ctx, "migration-eks-cluster-default-us-east-1");
        assert!(r.any_call_contains("eks update-kubeconfig"));
        assert!(r.any_call_contains("config use-context"));
        assert_eq!(
            app.state.get("EKS_CLUSTER"),
            Some("migration-eks-cluster-default-us-east-1")
        );
    }

    #[test]
    fn mirror_skips_when_disabled() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("MIRROR_IMAGES", "N");
        let values = app.mirror_images_and_charts().unwrap();
        assert!(values.is_empty());
    }

    #[test]
    fn helm_install_assembles_mirrored_flags_and_waits() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new()
            .stub("aws", &["describe-stacks"], 0, REAL_OUTPUTS)
            .stub("helm", &["status"], 1, "") // absent release
            .stub("helm", &["upgrade", "--install"], 0, "")
            .stub("kubectl", &["get", "namespace"], 0, "")
            .stub("kubectl", &["wait"], 0, "");
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("AWS_REGION", "us-east-1");
        app.state.set("AWS_ACCOUNT", "629003556176");
        app.state.set("STAGE_NAME", "ma");
        app.state.set("MA_VERSION", "3.2.1");
        app.state
            .set("CFN_STACK_NAME", "MigrationAssistant-default");
        app.state.set("MIRROR_IMAGES", "Y");
        app.state.set(
            "CRANE_REGISTRY",
            "629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1",
        );
        app.state.set("KUBECTL_CONTEXT", "ctx");

        app.helm_install_or_upgrade("/charts/ma.tgz", &["/values.yaml".into()])
            .unwrap();
        assert_eq!(app.state.resumable_step(), "helm_done");

        // The install now runs through a `bash -c <script>` wrapper (the script
        // streams namespace pod/events while `helm --wait` blocks). The helm
        // argv is embedded shell-quoted in that script; strip the single quotes
        // so token-adjacency assertions (`--kube-context ctx`) read naturally.
        let upgrade = r
            .calls_to("bash")
            .into_iter()
            .find(|c| c.joined().contains("upgrade") && c.joined().contains("--install"))
            .unwrap();
        let j = upgrade.joined().replace('\'', "");
        // Bound to the kube context.
        assert!(j.contains("--kube-context ctx"));
        // Mirrored image flags + snapshot role from CFN outputs.
        assert!(j.contains("images.captureProxy.tag=migrations_capture_proxy_3.2.1"));
        assert!(j.contains(
            "defaultBucketConfiguration.snapshotRoleArn=arn:aws:iam::629003556176:role/snap"
        ));
        assert!(j.contains("stageName=ma"));
        // Waited for the console pod.
        assert!(r.any_call_contains("pod/migration-console-0"));
    }

    #[test]
    fn image_tag_never_empty_on_build_lane() {
        // Regression: on --build with no --version, the image tag must default
        // to `latest`, NOT empty — an empty tag yields `migrations_<name>_`
        // which fails ErrImagePull → installer DeadlineExceeded.
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("BUILD_FROM_SOURCE", "Y"); // --build, no --version
        assert_eq!(app.resolve_image_tag(), "latest");
        // Mirrored flags must produce a non-empty, non-trailing-underscore tag.
        let flags = helm::mirrored_image_flags("reg", &app.resolve_image_tag());
        assert!(flags
            .iter()
            .any(|f| f == "images.installer.tag=migrations_migration_console_latest"));
        assert!(!flags.iter().any(|f| f.ends_with('_')));
        // Explicit IMAGE_TAG / BUILD_IMAGE_TAG win when set.
        app.state.set("BUILD_IMAGE_TAG", "gitsha123");
        assert_eq!(app.resolve_image_tag(), "gitsha123");
        app.state.set("IMAGE_TAG", "explicit");
        assert_eq!(app.resolve_image_tag(), "explicit");
    }

    // ---- headless-deploy artifact resolution + EKS access + build ----

    #[test]
    fn resolve_template_downloads_release_when_not_build() {
        let dir = tempfile::tempdir().unwrap();
        // curl writes the file the resolver checks for; emulate by stubbing curl
        // success AND pre-creating the dest the resolver computes.
        let r = MockRunner::new().stub("curl", &["-o"], 0, "");
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("MA_VERSION", "2.9.0");
        app.state.set("CFN_TEMPLATE_VARIANT", "create-vpc");
        // Pre-create the temp dest so the is_file() check passes (curl is mocked).
        let dest = std::env::temp_dir().join(format!("ma-template-{}.json", std::process::id()));
        std::fs::write(&dest, "{}").unwrap();
        let path = app.resolve_cfn_template().unwrap();
        assert_eq!(path, dest.to_string_lossy());
        let call = r.calls_to("curl").into_iter().next().unwrap().joined();
        assert!(call.contains(
            "releases/download/2.9.0/Migration-Assistant-Infra-Create-VPC-eks.template.json"
        ));
        let _ = std::fs::remove_file(&dest);
    }

    #[test]
    fn resolve_template_synthesizes_via_gradle_on_build() {
        let dir = tempfile::tempdir().unwrap();
        // Stage a fake repo with the synthesized template already present, since
        // the gradle call is mocked (success) and the resolver checks the path.
        let base = dir.path().join("repo");
        let synth = base.join("deployment/migration-assistant-solution/cdk.out-minified");
        std::fs::create_dir_all(&synth).unwrap();
        std::fs::write(
            synth.join("Migration-Assistant-Infra-Create-VPC-eks.template.json"),
            "{}",
        )
        .unwrap();
        let gradlew = format!("{}/gradlew", base.to_str().unwrap());
        let r = MockRunner::new().stub(&gradlew, &["cdkSynthMinified"], 0, "");
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("BUILD_FROM_SOURCE", "Y");
        app.state.set("BASE_DIR", base.to_str().unwrap());
        let path = app.resolve_cfn_template().unwrap();
        assert!(path.ends_with("Migration-Assistant-Infra-Create-VPC-eks.template.json"));
        assert!(r.any_call_contains("cdkSynthMinified"));
    }

    #[test]
    fn resolve_chart_build_returns_chart_dir_and_values() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("BUILD_FROM_SOURCE", "Y");
        app.state.set("BASE_DIR", "/repo");
        let (chart, values) = app.resolve_chart().unwrap();
        assert!(chart.ends_with("migrationAssistantWithArgo"));
        // values.yaml THEN valuesEks.yaml (EKS overrides) — the bug fix.
        assert_eq!(values.len(), 2);
        assert!(values[0].ends_with("/values.yaml"));
        assert!(values[1].ends_with("/valuesEks.yaml"));
    }

    #[test]
    fn resolve_chart_build_appends_extra_helm_values() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("BUILD_FROM_SOURCE", "Y");
        app.state.set("BASE_DIR", "/repo");
        app.state
            .set("HELM_EXTRA_VALUES_FILE", "/extra/values.yaml");
        let (_chart, values) = app.resolve_chart().unwrap();
        assert_eq!(values.len(), 3);
        assert_eq!(values[2], "/extra/values.yaml");
    }

    #[test]
    fn build_requires_base_dir() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("BUILD_FROM_SOURCE", "Y"); // no BASE_DIR
        let err = app.resolve_cfn_template().unwrap_err();
        assert!(err.message.contains("--base-dir"));
    }

    #[test]
    fn grant_eks_access_noop_without_arn() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        let app = App::load(env_in(dir.path()), &r).unwrap();
        app.grant_eks_access().unwrap();
        assert!(r.calls_to("aws").is_empty());
    }

    #[test]
    fn grant_eks_access_creates_and_associates() {
        let dir = tempfile::tempdir().unwrap();
        // describe-access-entry fails → create path is taken.
        let r = MockRunner::new()
            .stub("aws", &["describe-access-entry"], 254, "")
            .stub("aws", &["create-access-entry"], 0, "")
            .stub("aws", &["associate-access-policy"], 0, "");
        let app = {
            let mut a = App::load(env_in(dir.path()), &r).unwrap();
            a.state.set("AWS_REGION", "us-east-1");
            a.state.set("EKS_CLUSTER", "migration-eks-x");
            a.state.set(
                "EKS_ACCESS_PRINCIPAL_ARN",
                "arn:aws:iam::1:role/JenkinsDeploymentRole",
            );
            a
        };
        app.grant_eks_access().unwrap();
        assert!(r.any_call_contains("create-access-entry"));
        assert!(r.any_call_contains("associate-access-policy --cluster-name migration-eks-x"));
        assert!(r.any_call_contains("AmazonEKSClusterAdminPolicy"));
    }

    #[test]
    fn build_images_skipped_when_not_build_or_has_source() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        // not --build → skip
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.build_images_or_skip().unwrap();
        // --build but --ma-images-source set → also skip (mirror handles it)
        app.state.set("BUILD_FROM_SOURCE", "Y");
        app.state
            .set("MA_IMAGES_SOURCE", "1234.dkr.ecr.us-east-1.amazonaws.com");
        app.build_images_or_skip().unwrap();
        assert!(!r.any_call_contains("buildImagesToRegistry"));
    }

    #[test]
    fn build_images_runs_gradle_and_marks_mirrored() {
        let dir = tempfile::tempdir().unwrap();
        // The whole build (buildkit setup + ECR login + gradle) runs as one
        // `bash -c <script>`; stub it to succeed and assert on the script.
        let r = MockRunner::new()
            .stub("aws", &["describe-stacks"], 0, REAL_OUTPUTS)
            .stub("bash", &["buildImagesToRegistry"], 0, "");
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("BUILD_FROM_SOURCE", "Y");
        app.state.set("BASE_DIR", "/repo");
        app.state.set("AWS_REGION", "us-east-1");
        app.state.set("STAGE_NAME", "ma");
        app.state.set("MA_VERSION", "local-build");
        app.state.set("CFN_STACK_NAME", "MigrationAssistant-ma");
        app.state.set("KUBECTL_CONTEXT", "migration-eks-ma");
        app.state.set("SKIP_TEST_IMAGES", "Y");
        app.build_images_or_skip().unwrap();
        let g = r
            .calls_to("bash")
            .into_iter()
            .find(|c| c.joined().contains("buildImagesToRegistry"))
            .unwrap()
            .joined();
        assert!(g.contains("-PregistryEndpoint=\"629003556176.dkr.ecr.us-east-1.amazonaws.com"));
        assert!(g.contains("-Pbuilder=\"$BUILDER_NAME\""));
        assert!(g.contains("BUILDER_NAME=\"builder-migration-eks-ma\""));
        assert!(g.contains("setup_build_backend"));
        assert!(g.contains("-PskipTestImages=true"));
        // Marks the MA-image build done — but does NOT mark crane "done": the
        // third-party images + charts still need mirroring afterward.
        assert_eq!(app.state.get("BUILD_IMAGES_DONE"), Some("Y"));
        assert_ne!(app.state.get("CRANE_MIRRORED"), Some("1"));
    }

    #[test]
    fn mirror_skipped_when_public_images() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new();
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("MIRROR_IMAGES", "N");
        let v = app.mirror_images_and_charts().unwrap();
        assert!(v.is_empty());
        assert!(r.calls_to("helm").is_empty());
    }

    #[test]
    fn mirror_fails_without_ecr_credentials() {
        let dir = tempfile::tempdir().unwrap();
        let r = MockRunner::new().stub("aws", &["describe-stacks"], 0, REAL_OUTPUTS);
        let mut app = App::load(env_in(dir.path()), &r).unwrap();
        app.state.set("MIRROR_IMAGES", "Y");
        app.state.set("AWS_REGION", "us-east-1");
        app.state.set("CFN_STACK_NAME", "MigrationAssistant-ma");
        // No real ECR creds in test env → mirror fails.
        let result = app.mirror_images_and_charts();
        assert!(result.is_err());
    }
}
