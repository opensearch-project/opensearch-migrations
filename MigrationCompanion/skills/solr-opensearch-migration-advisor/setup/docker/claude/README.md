### Claude Agent packaging

The container-packaged claude agent provides an isolated setup with claude installation 
and startup script to build the image, start up the container, switch into container shell and start up claude.
The container already contains all needed files.

- Image needs initial build and rebuild after content changes: `build_image.sh`
  - To build the image, we change the working dir to `solr-opensearch-migration-advisor` root since docker build needs 
    to be in hierarchy of needed files, not in deeper subdirectories.
- simply run `start_container.sh` for startup of container and claude session
  within container from host shell. After running it you find yourself directly in the container shell with 
  a running claude code session and migration agent skills discoverable.
  - make sure env var `CLAUDE_CODE_OAUTH_TOKEN` is set in `solr-opensearch-migration-advisor/.env`
  - in case you dont have such a token yet, you can generate it via `claude setup-token`. This allows you to use
    your existing claude code subscription rather than needing another api token.
