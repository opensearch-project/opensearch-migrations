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
    parser.add_argument("--source", choices=["kafka", "s3"], default="kafka",
                        help="Where to read tuples from (default: kafka). 's3' polls the "
                        "same tuples written to S3 instead, mirroring 06-live-jaccard.sh — "
                        "real, rotation-interval-scale lag versus Kafka's near-real-time feed.")
    parser.add_argument("--s3-bucket", default="migrations-default-123456789012-dev-us-east-2",
                        help="S3 bucket tuples are written to (only used with --source s3)")
    parser.add_argument("--s3-prefix", default="tuples/",
                        help="S3 key prefix tuples are written under (only used with "
                        "--source s3, default: tuples/)")
    parser.add_argument("--poll-interval", type=float, default=10.0,
                        help="Seconds between S3 polls (only used with --source s3, "
                        "default: 10.0)")
    args = parser.parse_args()

    app = JaccardApp(
        namespace=args.namespace,
        topic=args.topic,
        window=args.window,
        refresh_interval=args.interval,
        auto_create_topic=not args.no_auto_create_topic,
        source=args.source,
        s3_bucket=args.s3_bucket,
        s3_prefix=args.s3_prefix,
        poll_interval=args.poll_interval,
    )
    app.run()


if __name__ == "__main__":
    main()
