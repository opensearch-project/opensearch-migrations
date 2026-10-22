"""Write the Workflow Manage OpenAPI document."""

import argparse
import json
from pathlib import Path
from typing import Optional, Sequence

from .app import create_app


def main(argv: Optional[Sequence[str]] = None) -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    document = json.dumps(create_app().openapi(), indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(document, encoding="utf-8")
    else:
        print(document, end="")


if __name__ == "__main__":
    main()
