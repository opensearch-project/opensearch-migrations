async def delete_snapshot(opensearch, params):
    await opensearch.snapshot.delete(repository=params["repository"], snapshot=params["snapshot"])


def register(registry):
    registry.register_runner("delete-snapshot", delete_snapshot, async_runner=True)
