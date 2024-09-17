# Migration Timelines

## 15 day


### Timeline

```mermaid
%%{
  init: {
    'theme': 'base',
    'themeVariables': {
      'primaryColor': '#2070D0',
      'primaryTextColor': '#000000',      
      'primaryBorderColor': '#002060',
      'tertiaryColor': '#FFFFFF',
      'taskTextColor': '#00000'
    },
    "gantt": {
        "fontSize": 20,
        "barHeight": 40,
        "sectionFontSize": 24,
        "leftPadding": 175
    }
  }
}%%
gantt
    dateFormat D HH
    axisFormat Day %d
    todayMarker off
    tickInterval 1day

    section Steps
    Deployment : milestone, deployment, 1 00, 0d
    Setup and Verification : setup, after deployment, 5d
    Clear Test Environment : milestone, clear, after setup, 0d
    Traffic Capture : traffic_capture, after clear, 5d
    Snapshot : snapshot, after clear, 1d
    Metadata Migration : metadata, after snapshot, 1h
    Scale Up Target Cluster : milestone, after metadata, 0d
    Reindex from Snapshot : rfs, after metadata, 71h
    Scale Down Target Cluster for Replay : milestone, after rfs, 0d
    Traffic Replay (5x): replay, after rfs, 1d
    Traffic Switchover : milestone, switchover, after replay, 0d
    Validation : validation, after switchover, 3d
    Scale Down Target Cluster : milestone, 12 00, 0d
    Teardown   : teardown, after validation, 2d
```

### Component Durations

```mermaid
%%{
  init: {
    'theme': 'base',
    'themeVariables': {
      'primaryColor': '#2070D0',
      'primaryTextColor': '#000000',      
      'primaryBorderColor': '#002060',
      'tertiaryColor': '#FFFFFF',
      'taskTextColor': '#00000'
    },
    "gantt": {
        "fontSize": 20,
        "barHeight": 40,
        "sectionFontSize": 24,
        "leftPadding": 175
    }
  }
}%%
gantt
    dateFormat D HH
    axisFormat Day %d
    todayMarker off
    tickInterval 1day

    section Services
    %% Duration used excludes weekend time which adds costs
    Core Services Runtime (15d) : active, 1 00, 15d
    Capture Proxy Runtime (5d) : active, capture_active, 6 00, 5d
    Capture Data Retention (5d) : after capture_active, 5d
    Snapshot Runtime (1d) : active, snapshot_active, 6 00, 1d
    Snapshot Retention (9d) : after snapshot_active, 9d
    Reindex from Snapshot Runtime (3d) : active, historic_active, 7 01, 71h
    Replayer Runtime (1d) : active, replayer_active, after historic_active, 1d
    Replayer Data Retention (5d) : after replayer_active, 5d
    Target Proxy Runtime (5d) : active, after replayer_active, 5d
```

| Component                         | Duration |
|-----------------------------------|----------|
| Core Services Runtime             | 15d      |
| Capture Proxy Runtime             | 5d       |
| Capture Data Retention            | 5d       |
| Snapshot Runtime                  | 1d       |
| Snapshot Retention                | 9d       |
| Reindex from Snapshot Runtime     | 3d       |
| Replayer Runtime                  | 1d       |
| Replayer Data Retention           | 5d       |
| Target Proxy Runtime              | 5d       |
