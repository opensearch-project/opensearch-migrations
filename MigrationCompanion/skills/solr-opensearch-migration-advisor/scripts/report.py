from typing import List, Dict, TYPE_CHECKING

if TYPE_CHECKING:
    from storage import Incompatibility, ClientIntegration


class MigrationReport:
    """Generates a migration report from session context."""

    def __init__(
        self,
        milestones: List[str] = None,
        blockers: List[str] = None,
        implementation_points: List[str] = None,
        cost_estimates: Dict[str, str] = None,
        incompatibilities: List["Incompatibility"] = None,
        client_integrations: List["ClientIntegration"] = None,
    ):
        self.milestones = milestones or []
        self.blockers = blockers or []
        self.implementation_points = implementation_points or []
        self.cost_estimates = cost_estimates or {}
        self.incompatibilities = incompatibilities or []
        self.client_integrations = client_integrations or []

    def generate(self) -> str:
        report = []
        report.append("# Solr to OpenSearch Migration Report\n")
        
        self._generate_incompatibilities_section(report)
        self._generate_client_integrations_section(report)
        self._generate_milestones_section(report)
        self._generate_blockers_section(report)
        self._generate_implementation_section(report)
        self._generate_cost_estimates_section(report)
        
        return "\n".join(report)

    def _generate_incompatibilities_section(self, report: List[str]) -> None:
        """Generate the incompatibilities section of the report."""
        report.append("## Incompatibilities")
        if self.incompatibilities:
            for severity in ("Breaking", "Unsupported", "Behavioral"):
                items = [i for i in self.incompatibilities if i.severity == severity]
                if not items:
                    continue
                report.append(f"\n### {severity}")
                for item in items:
                    report.append(f"- **[{item.category}]** {item.description}")
                    report.append(f"  - *Recommendation:* {item.recommendation}")
            critical = [
                i for i in self.incompatibilities
                if i.severity in ("Breaking", "Unsupported")
            ]
            if critical:
                report.append(
                    "\n> **Action required:** The items above marked Breaking or "
                    "Unsupported must be resolved before cutover."
                )
        else:
            report.append("- No incompatibilities identified.")
        report.append("")

    def _generate_client_integrations_section(self, report: List[str]) -> None:
        """Generate the client & front-end impact section of the report."""
        report.append("## Client & Front-end Impact")
        if self.client_integrations:
            self._render_client_integrations_by_kind(report)
        else:
            report.append("- No client or front-end integrations recorded.")
        report.append("")

    def _render_client_integrations_by_kind(self, report: List[str]) -> None:
        """Render client integrations grouped by kind."""
        kind_order = ["library", "ui", "http", "other"]
        kind_labels = {
            "library": "Client Libraries",
            "ui": "Front-end / UI",
            "http": "HTTP / Custom Clients",
            "other": "Other Integrations",
        }
        rendered_kinds = set()
        
        for kind in kind_order:
            items = [c for c in self.client_integrations if c.kind == kind]
            if not items:
                continue
            rendered_kinds.add(kind)
            report.append(f"\n### {kind_labels[kind]}")
            for c in items:
                self._render_client_integration(report, c)
        
        # Catch any kinds not in kind_order
        for c in self.client_integrations:
            if c.kind not in rendered_kinds and c.kind not in kind_order:
                report.append(f"- **{c.name}** ({c.kind})")
                if c.notes:
                    report.append(f"  - *Current usage:* {c.notes}")
                report.append(f"  - *Migration action:* {c.migration_action}")

    def _render_client_integration(self, report: List[str], integration: "ClientIntegration") -> None:
        """Render a single client integration entry."""
        report.append(f"- **{integration.name}**")
        if integration.notes:
            report.append(f"  - *Current usage:* {integration.notes}")
        report.append(f"  - *Migration action:* {integration.migration_action}")

    def _generate_milestones_section(self, report: List[str]) -> None:
        """Generate the major milestones section of the report."""
        report.append("## Major Milestones")
        for i, m in enumerate(self.milestones, 1):
            report.append(f"{i}. {m}")
        report.append("")

    def _generate_blockers_section(self, report: List[str]) -> None:
        """Generate the potential blockers section of the report."""
        report.append("## Potential Blockers")
        for b in self.blockers:
            report.append(f"- {b}")
        if not self.blockers:
            report.append("- No immediate blockers identified.")
        report.append("")

    def _generate_implementation_section(self, report: List[str]) -> None:
        """Generate the implementation points section of the report."""
        report.append("## Implementation Points")
        for ip in self.implementation_points:
            report.append(f"- {ip}")
        report.append("")

    def _generate_cost_estimates_section(self, report: List[str]) -> None:
        """Generate the cost estimates section of the report."""
        report.append("## Cost Estimates")
        for item, est in self.cost_estimates.items():
            report.append(f"- **{item}**: {est}")
        if not self.cost_estimates:
            report.append("- TBD based on further infra analysis.")
