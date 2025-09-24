from functools import wraps
from threading import RLock


def with_lock(lock: RLock):
    """Decorator factory that wraps a function with the given lock."""
    def decorator(fn):
        @wraps(fn)
        def _wrapped(*args, **kwargs):
            with lock:
                return fn(*args, **kwargs)
        return _wrapped
    return decorator
