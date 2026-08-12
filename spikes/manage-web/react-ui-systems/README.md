# React UI System Spike

This workspace compares two UI-system strategies for the selected React frontend:

- Cloudscape Design System;
- React Aria Components with application-owned styling.

The completed comparison rejected Cloudscape for production use. Both pages remain here
as design evidence; neither is production scaffolding. The production application will
own its layout and styling and may use focused accessible React libraries by capability.

Both pages use the same `@manage-spike/shared` fixture, `useManageTree` state controller,
and custom `ResourceTree`. The comparison therefore measures the application shell,
forms, tabs, status, operations, logs, responsive behavior, styling ownership, and package
cost without changing the specialized tree behavior.

## Run

From `spikes/manage-web`:

```sh
npm run dev:ui-systems
```

Open:

- `http://127.0.0.1:4180/cloudscape.html`
- `http://127.0.0.1:4180/react-aria.html`

Build both pages:

```sh
npm run build --workspace @manage-spike/react-ui-systems
```

## Evaluation Sequence

Perform the same sequence in each page:

1. Select `capture-proxy` and trigger refresh.
2. Enter edit mode and observe configuration nodes enter the existing tree.
3. Select `Trusted client CA`; compare ConfigMap and key selection.
4. Select `traffic-replayer`, open Configuration, and add a transform.
5. Start logs, navigate to another tab, return, and stop the stream.
6. Start Approve or Reset and inspect persistent operation progress.
7. Repeat at a narrow viewport.

Do not compare default appearance alone. Compare information density, focus behavior,
custom tree integration, form ergonomics, responsive transitions, CSS ownership, and how
much library-specific structure enters feature components.
