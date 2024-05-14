# Traffic Capture/Replay Instrumentation

This "coreUtilities" package contains common classes and interfaces used to facilitate instrumentation for metrics and
traces.

## Approach

The package takes a hard dependency on OpenTelemetry ('otel'). OpenTelemetry provides a unified interface to a
variety of tracing and metering systems. From that unified interface, metric instruments and traces (or "spans") can
be sent to a variety of metric and tracing platforms, including Prometheus, Jaeger, and cloud native solutions like
Amazon CloudWatch and AWS X-Ray.
[RootOtelContext](src/main/java/org/opensearch/migrations/tracing/RootOtelContext.java) acts as a factory for metric
instruments and trace spans.  As it is currently implemented, both metrics and spans are exported via
[OTLP](https://opentelemetry.io/docs/specs/otel/protocol/) to an
[OTEL Collector](https://opentelemetry.io/docs/collector/) that proxies instrumentations through further processing
and into downstream systems via exporters.

It would be redundant to try to make another generic library, so the goal of this package is not to make it easier to
switch instrumentation platforms, but to make instrumentation fit with the TrafficCapture packages more naturally. As
a gradle project, this dependency is exposed as an "api" dependency so that other consumer packages will automatically
pick up the dependency as if it were their own dependency.

Some of the OpenTelemetry patterns don't work naturally for this asynchronous code with dependency injection.
Specifically, try-with-resources and the use of stack frames to determine contexts are more trouble than they're worth.
Similarly, using statics to store meter values make the code more rigid and can make testing in parallel more difficult.

This library adapts the parts of OpenTelemetry to make it more natural and more foolproof throughout the rest of the
TrafficCapture packages. This package introduces the concept of "Contexts" to build manage all tracing and metering
instrumentation.

Just as the otel metering and tracing can be efficiently disabled by not configuring them, this library provides some
future-proofing by defining interfaces to track attributes, activities, exceptions, etc - but through descriptive
interfaces where callers describe which actions they're performing, preventing the code from becoming overly complex.

The goals of the instrumentation package are to

1. make instrumentation classes easy to use.
2. make it easy to create new safe and easy to use instrumentation classes.
3. be efficient enough to use in most cases and flexible enough to tune in cases where the cost is too high.

The third point is still a work in progress as the exact performance penalty isn't understood yet. However, work for
point #2 dovetails into #3.  As context creations are chained together, a no-op uber-context can be created with zero
memory footprint and minimal CPU penalty.  The first couple points are accomplished by putting contextual information
alongside other data as first class parameters and fields. For example, where a method might require an identifier,
a context might be passed instead so that the function can retrieve identifying information via the context AND have
the ability to instrument activity within the appropriate context.

## Class Structure Contexts

All metering and tracing activity within the TrafficCapture packages occurs via "Contexts" which are implementations of
either [IInstrumentationAttributes](src/main/java/org/opensearch/migrations/tracing/IInstrumentationAttributes.java) or
its extension,
[IScopedInstrumentationAttributes](src/main/java/org/opensearch/migrations/tracing/IScopedInstrumentationAttributes.java).
IInstrumentationAttributes allows callers to meter activities into counters and histograms via
[otel instruments](https://opentelemetry.io/docs/concepts/signals/metrics/#metric-instruments). Callers need not know
any specific metric structures in order to add activities.  Instead, contexts expose progress APIs that fit the
components that they're designed to work with.

For example, the
[RequestTransformationContext](../trafficReplayer/src/main/java/org/opensearch/migrations/replay/tracing/ReplayContexts.java)
class tracks network activity and performance of the http transformation code. That class manages how those
interactions and values are converted to otel instrumentation. That frees application code from implementation details,
makes the application cleaner, and allows all instrumentation concerns to be consolidated.

IScopedInstrumentationAttributes extensions also provide (auto) instrumentation to indicate when the activities that
they represent began and ended.  That information includes the duration of the activity represented by the context,
along with a count of the occurrences of the activity.  In addition to those metrics, spans are also created and
emitted as the context is closed.

The base Attributes interfaces (IInstrumentationAttributes and IScopedInstrumentationAttributes) provide functions to
fill in attributes that are specific to metrics and, independently, specific to spans.  Metric values are aggregated
and the more unique attribute combinations possible for each time bucket, the larger the stress on the time-series
database.  However, varied attributes can, in some circumstances, be worth the price of extra space and processing time.
Consider the metrics to show status code differences between the source and target clusters.

In addition to those baseline features, some Context classes (that extend the Attributes interfaces) are capable of
creating child contexts that have a parent relationship with the creating Context.

## OpenTelemetry Specifics

While metric instruments can be emitted without any span context, after all the two systems receiving those values are
unrelated, emitting metrics from within a [Scope](https://opentelemetry.io/docs/concepts/instrumentation-scope/) allows
metrics to be linked to [exemplar](https://opentelemetry.io/docs/specs/otel/metrics/data-model/#exemplars)
spans.  When Prometheus is used as a metrics data sink, as it is configured in the dockerSolution
(with '--enable-feature=exemplar-storage'), exemplars can be rendered in the same graph as the general data points.

Since exact values can't be stored within a metrics data store, but we still have a need to render percentiles of
those results, OpenTelemetry uses bucketed histograms.  The Contexts will automatically convert a numerical value (or
will calculate the number of milliseconds from the time that the Context was created) into a histogram.