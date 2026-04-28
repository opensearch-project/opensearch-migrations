### Claude Agent packaging

The container-packaged Claude agent provides an isolated setup with Claude installation,
the OpenSearch pricing calculator, and a startup script to bring everything up and drop
you directly into a running Claude session.

#### Prerequisites

- Docker with Compose support (`docker compose`)
- A valid `CLAUDE_CODE_OAUTH_TOKEN` set in `solr-opensearch-migration-advisor/.env`
  - If you don't have a token yet, generate one with `claude setup-token`. This lets you
    use your existing Claude Code subscription rather than a separate API key.

#### Starting

From anywhere in the repo, run:

```bash
./setup/docker/claude/start_container.sh
```

This will:
1. Build the `claude_image` from the `Dockerfile` (using the repo root as build context)
2. Start the `opensearch-pricing-calculator` and `claude-container` services via `docker-compose.yml`
3. `docker exec -it` into the running `claude-container` and launch Claude

Both services share the `opensearch-migration` Docker network so the pricing calculator
is reachable at `http://opensearch-pricing-calculator:5050` from within the Claude container.

Work is persisted in `setup/docker/claude/CLAUDE_ADVISOR_VOLUME/`, which is mounted into
the container at `/home/user/claude/processing`.

#### Stopping

```bash
./setup/docker/claude/stop_container.sh
```

#### Rebuilding after content changes

`start_container.sh` passes `--build` to `docker compose up`, so the image is automatically
rebuilt whenever you run it. To force a rebuild without starting a session:

```bash
docker compose -f setup/docker/claude/docker-compose.yml build
```
