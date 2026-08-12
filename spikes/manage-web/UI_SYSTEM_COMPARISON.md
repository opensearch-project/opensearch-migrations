# React UI System Comparison

Status: implementation spike; decision not yet settled

## Options

### Cloudscape Design System

Cloudscape provides the application shell and visual system. The spike uses `AppLayout`,
`TopNavigation`, `ContentLayout`, `SplitPanel`, `Tabs`, forms, alerts, status indicators,
and progress components. The resource tree and log console remain custom.

Advantages:

- responsive navigation drawer and side/bottom operation panel are built in;
- controls, status, validation, focus treatment, and spacing are visually coherent;
- less application CSS is needed for common operational UI;
- AWS users will recognize the interaction patterns;
- the custom tree can live inside the navigation slot without adopting a Cloudscape data
  model.

Costs and constraints:

- the application strongly resembles the AWS console even though the product is not
  AWS-specific;
- the generated prototype bundle is substantially larger;
- global styles and layout behavior become part of the application foundation;
- specialized surfaces still require custom styling and careful integration;
- compact or unconventional workflows may need overrides against a strongly opinionated
  layout.

### React Aria Components And Owned Styling

React Aria supplies typed accessible behavior for tabs, buttons, fields, selects, switches,
popovers, listboxes, and progress. The application owns layout and appearance.

Advantages:

- complete control over information density, hierarchy, and specialized widgets;
- no AWS or OpenSearch product identity is introduced;
- substantially smaller generated prototype bundle;
- accessible interaction behavior does not require hand-building every primitive;
- library types stay near controls rather than shaping application layout or domain state.

Costs and constraints:

- the application must own responsive layout, styling tokens, and visual consistency;
- operation drawers, navigation collapse, and page-shell behavior require implementation;
- more CSS and visual regression coverage are necessary;
- design quality depends on maintaining deliberate conventions across features.

## Build Evidence

Vite production output for the two-page spike:

| Asset loaded by page | Raw | Gzip |
| --- | ---: | ---: |
| Shared React/tree/scenario JavaScript | 164.32 kB | 52.38 kB |
| Cloudscape-specific JavaScript | 696.43 kB | 190.59 kB |
| Cloudscape-specific CSS | 881.16 kB | 219.69 kB |
| React Aria-specific JavaScript | 215.16 kB | 67.93 kB |
| React Aria-specific CSS | 12.74 kB | 2.93 kB |
| Shared tree CSS | 5.66 kB | 1.92 kB |

These are prototype measurements, not final budgets. They include the controls exercised by
the spike and Cloudscape global styles. Production code splitting, dependency updates, and
different control coverage can change them, but the order-of-magnitude difference is
relevant.

## Findings

Both options preserve localized tree insertion, selection, focus, expansion, and animation
because that behavior remains in an application-owned component keyed by stable node IDs.
Neither library needs to own the resource tree.

Cloudscape reaches a coherent operational shell faster. Its strongest result is responsive
shell behavior: navigation becomes a drawer and operations move from a side panel to a
bottom split panel. Its largest tradeoffs are visual identity, package weight, and
foundation-level layout coupling.

React Aria provides a better escape path for specialized interaction. The application owns
more CSS and responsive behavior, but the resulting feature components are not constrained
to an AWS-console composition.

## Provisional Direction

Prefer React Aria Components with application-owned styling unless implementation speed,
AWS-console familiarity, and Cloudscape's responsive shell are judged more important than
visual independence and package cost.

This remains reversible while feature components depend on domain DTOs and application
state rather than Cloudscape or React Aria types. It becomes expensive after the production
shell, forms, dialogs, and operation surfaces are implemented broadly. Make the final
choice before that work begins.
