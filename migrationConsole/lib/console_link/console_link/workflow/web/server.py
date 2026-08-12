"""Production ASGI runner for the native workflow manage application."""

import argparse
from pathlib import Path
from typing import Optional, Sequence

import uvicorn

from .app import create_app


def main(argv: Optional[Sequence[str]] = None) -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8000, type=int)
    parser.add_argument("--static-dir", type=Path)
    args = parser.parse_args(argv)
    uvicorn.run(
        create_app(static_dir=args.static_dir),
        host=args.host,
        port=args.port,
        access_log=True,
    )


if __name__ == "__main__":
    main()
