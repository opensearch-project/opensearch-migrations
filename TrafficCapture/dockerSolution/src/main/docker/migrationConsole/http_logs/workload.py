from osbenchmark.workload import loader


def reindex(es, params):
    result = es.reindex(body=params.get("body"), request_timeout=params.get("request_timeout"))
    return result["total"], "docs"


async def reindex_async(es, params):
    result = await es.reindex(body=params.get("body"), request_timeout=params.get("request_timeout"))
    return result["total"], "docs"


def register(registry):
    async_runner = registry.meta_data.get("async_runner", False)
    if async_runner:
        registry.register_runner("reindex", reindex_async, async_runner=True)
    else:
        registry.register_runner("reindex", reindex)
    try:
        registry.register_workload_processor(loader.DefaultWorkloadPreparator())
    except TypeError as e:
        if e == "__init__() missing 1 required positional argument: 'cfg'":
            pass
