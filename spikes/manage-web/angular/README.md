# Angular Manage Spike

This spike uses Angular standalone components, strict templates, signals, RxJS, Angular CDK
accessibility utilities, and custom styling. Angular Material is not used.

The tree consumes the shared `TreePatch` fixtures. Rows are tracked by stable node ID, so
entering edit mode, adding a transform, or refreshing unrelated status preserves existing
DOM elements, selection, focus, and expansion. Newly inserted rows receive a short entry
animation without replacing the surrounding tree.

## Run

From `spikes/manage-web`:

```sh
npm install
npm run dev:angular
```

Open `http://localhost:4200`.

To build or run the focused DOM identity tests:

```sh
npm run build --workspace @manage-spike/angular
npm run test --workspace @manage-spike/angular
```

The test command requires a local Chrome or Chromium installation.
