# React Manage Web Spike

React 18 and TypeScript prototype for the native `workflow manage` interface. The tree
applies shared `TreePatch` values by stable node ID and keeps selection, focus, expansion,
and existing DOM rows intact while edit nodes are inserted.

## Run

From `spikes/manage-web`:

```sh
npm install
npm run dev:react
```

Open <http://127.0.0.1:4173>.

## Verify

```sh
npm run build --workspace @manage-spike/react
npm run test --workspace @manage-spike/react
```
