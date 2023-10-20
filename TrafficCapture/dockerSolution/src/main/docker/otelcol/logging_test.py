import logging

from opentelemetry._logs import set_logger_provider
from opentelemetry.exporter.otlp.proto.grpc._log_exporter import (
    OTLPLogExporter,
)
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.resources import Resource
# from opentelemetry.sdk.trace.export import (
#     BatchSpanProcessor,
#     ConsoleSpanExporter,
# )


logger_provider = LoggerProvider(
    resource=Resource.create(
        {
            "service.name": "traffic_replayer",
            "service.instance.id": "default",
        }
    ),
)
set_logger_provider(logger_provider)

exporter = OTLPLogExporter(insecure=True, endpoint='0.0.0.0:4317')
logger_provider.add_log_record_processor(BatchLogRecordProcessor(exporter))
handler = LoggingHandler(level=logging.INFO, logger_provider=logger_provider)

# Attach OTLP handler to root logger
logging.getLogger().addHandler(handler)

# Log directly
logging.warning("Jackdaws love my big sphinx of quartz.")

logging.warning("Response received",
                extra={"connectionId": "0242acfffe120008-0000000f-00000073-16f35472b4a8f067-bcdfb6a7.0",
                        "httpStatus": "200 OK",
                        "httpMethod": "POST",
                        "latencyInMs": 42}
                )

print("finished")