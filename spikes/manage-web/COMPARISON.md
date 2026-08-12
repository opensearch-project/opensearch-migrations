# Angular, React, and Vue Spike Comparison

All three implementations consume the same TypeScript state, patch operations, and
configuration-control union. All three preserve exact tree-row DOM identity, focus,
selection, and expansion while applying status updates and inserting edit nodes.

The partial-update requirement does not disqualify any framework. Stable semantic IDs and
patch-oriented application state matter more than the rendering framework.

## Results

| Characteristic | Angular | React | Vue |
| --- | --- | --- | --- |
| Framework version | Angular 18 | React 18 + Vite 5 | Vue 3 + Vite 5 |
| Update mechanism | Signals and `@for track` | State reducer and keyed rows | Refs and keyed `TransitionGroup` |
| Focus/identity tests | 3 passed | 3 passed | 3 passed |
| Production JS, raw | About 289 KB | About 184 KB | About 115 KB |
| Production CSS, raw | About 1 KB external; component CSS is bundled | About 22 KB | About 18 KB |
| Visual component suite | None; CDK behavior only | None | None |
| Edit insertion animation | CSS class on tracked rows | CSS class plus inserted-ID lifecycle | Built-in `TransitionGroup` lifecycle |

Raw bundle sizes are useful only as directional evidence. These are small, independently
implemented spikes with different framework packaging and no production optimization
budget.

## Angular

### Advantages

- Most integrated application platform: router, HTTP, dependency injection, reactive
  forms, signals, RxJS, testing conventions, and CDK behavior are coordinated.
- Typed reactive forms fit a large schema-driven editor.
- Strict template checking catches incorrect bindings and component inputs.
- `@for (...; track id)` preserves row identity predictably.
- CDK provides accessibility, overlays, focus management, and virtualization without
  requiring Material styling.
- Strong conventions reduce architectural variation as the application and team grow.

### Costs

- Largest runtime and dependency surface in this comparison.
- More framework concepts and ceremony for a focused internal application.
- Signals, RxJS, and reactive forms create multiple reactive models that need explicit
  ownership rules.
- Browser tests require more infrastructure and startup work.
- TypeScript discriminated-union exhaustiveness is less direct when rendering logic lives
  primarily in templates.
- Highly specialized widgets are possible, but tend to cross more Angular-specific
  boundaries.

## React

### Advantages

- Strongest ecosystem for specialized trees, editors, virtualization, accessibility
  primitives, and operational interfaces.
- TSX provides direct TypeScript narrowing and straightforward exhaustive switches for
  schema control unions.
- Stable keyed reconciliation behaved predictably in the partial-update tests.
- Flexible enough to keep the design independent of OUI or any other visual system.
- The OpenSearch Dashboards ecosystem uses React, which is informative if integration or
  contributor familiarity ever matters, without requiring an OSD dependency.
- Large contributor and hiring familiarity.

### Costs

- React supplies the view layer, leaving forms, server state, routing, accessibility
  primitives, and larger state architecture as separate choices.
- More package evaluation and integration work is required.
- The spike needed explicit inserted-ID state and timers for row-entry animation.
- Effects, memoization, callback identity, and asynchronous state can add incidental code.
- Without firm local conventions, different features can adopt incompatible state and form
  patterns.

## Vue

### Advantages

- Smallest production artifact in these spikes.
- Composition API is concise while retaining strong TypeScript component props and state.
- Keyed `TransitionGroup` made localized insertion/removal animation the most direct of the
  three implementations.
- Single-file components keep specialized widget behavior, template, and styling close
  together without requiring a visual component suite.
- Reactivity is explicit enough for patch-driven state while requiring less framework
  ceremony than Angular.
- Vue Test Utils and Vitest made focused DOM-identity tests quick to set up.

### Costs

- Template type checking through `vue-tsc` is strong, but exhaustive discriminated-union
  rendering is less obvious than a TypeScript `switch` in TSX.
- Event handlers for union-specific controls still need explicit script-side type guards.
- Vue provides fewer integrated application services than Angular, so routing, server
  state, and broader conventions still need decisions.
- Its ecosystem is smaller than React's for unusual operational widgets.
- Complex Composition API code needs project conventions to avoid large components with
  loosely related refs and watchers.
- It has no particular alignment with the existing OpenSearch frontend ecosystem, though
  this standalone tool does not require that alignment.

## Practical Reading

- Choose Angular when a comprehensive, prescribed application platform and typed forms
  outweigh bundle size and framework ceremony.
- Choose React when ecosystem breadth, TSX exhaustiveness, and maximum library choice are
  most important.
- Choose Vue when a compact custom application, direct reactivity, and first-class
  transitions are more valuable than React's ecosystem or Angular's integrated platform.

The next comparison should use one genuinely difficult production widget, such as a
virtualized tree with inline schema editing and server-driven patches. The current spikes
show that each framework can preserve interaction state; they do not yet prove how each
behaves at production tree size or with real API concurrency.
