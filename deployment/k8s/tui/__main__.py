"""Entry point: python3 -m tui [options]

Run from deployment/k8s/ (or anywhere with this directory on PYTHONPATH), against whatever
cluster the local kubeconfig points at — same operating model as the shell script this
replaces.
"""
import argparse

from .app import JaccardApp


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Live replay-quality monitor for a TrafficReplayer tuple-output Kafka topic.")
    parser.add_argument("--namespace", "-n", default="ma",
                        help="Kubernetes namespace the migration console runs in (default: ma)")
    parser.add_argument("--topic", default="tuple-output",
                        help="Kafka topic to consume (default: tuple-output)")
    parser.add_argument("--window", type=int, default=15,
                        help="Number of scored tuples shown in the sparkline/table (default: 15)")
    parser.add_argument("--interval", type=float, default=2.0,
                        help="UI repaint interval in seconds (default: 2.0)")
    parser.add_argument("--no-auto-create-topic", action="store_true",
                        help="Fail instead of creating the topic if it does not exist")
    args = parser.parse_args()

    app = JaccardApp(
        namespace=args.namespace,
        topic=args.topic,
        window=args.window,
        refresh_interval=args.interval,
        auto_create_topic=not args.no_auto_create_topic,
    )
    app.run()


if __name__ == "__main__":
    main()
