# Vue Manage Web Spike

Vue 3 and TypeScript prototype for the native `workflow manage` interface. The tree
consumes the shared `TreePatch` model and renders its flat visible rows through a keyed
`TransitionGroup`. Existing rows keep their DOM identity, focus, selection, and expansion
while edit branches animate into place.

No visual component framework is used.

## Run

From `spikes/manage-web`:

```sh
npm install
npm run dev:vue
```

Open <http://127.0.0.1:4400>.

## Verify

```sh
npm run build --workspace @manage-spike/vue
npm run test --workspace @manage-spike/vue
```
