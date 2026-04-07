package org.opensearch.migrations.transform;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Base class for GraalVM polyglot transformers (JavaScript, Python, etc.).
 *
 * <p>Handles context creation, script evaluation, bindings initialization,
 * logging stream redirection, and the transformJson lifecycle.
 *
 * <p><strong>Not thread-safe.</strong> Each thread should use its own instance.
 */
@Slf4j
public abstract class GraalTransformer implements IJsonTransformer {
    private Value mainTransformFunction;
    private final Context polyglotContext;
    private final OutputStream infoStream;
    private final OutputStream errorStream;

    /**
     * @param languageId  GraalVM language identifier (e.g. "js", "python")
     * @param script      source code in the target language
     * @param bindings    object passed to the script's main function, or null
     * @param contextBuilder pre-configured context builder (subclass provides language-specific setup)
     */
    protected GraalTransformer(String languageId, String script, Object bindings,
                               Context.Builder contextBuilder) {
        var loggerName = getClass().getSimpleName();
        var scriptLogger = LoggerFactory.getLogger(loggerName);
        this.infoStream = new LoggingOutputStream(scriptLogger, Level.INFO);
        this.errorStream = new LoggingOutputStream(scriptLogger, Level.ERROR);

        this.polyglotContext = contextBuilder
            .allowHostAccess(HostAccess.newBuilder()
                .allowAccessAnnotatedBy(HostAccess.Export.class)
                .allowArrayAccess(true)
                .allowMapAccess(true)
                .allowListAccess(true)
                .allowIterableAccess(true)
                .allowBufferAccess(true)
                .build())
            .out(infoStream)
            .err(errorStream)
            .build();

        var sourceCodeValue = this.polyglotContext.eval(Source.create(languageId, script));
        if (bindings != null) {
            this.mainTransformFunction = sourceCodeValue.execute(this.polyglotContext.asValue(bindings));
        } else {
            this.mainTransformFunction = sourceCodeValue;
        }
    }

    @Override
    public Object transformJson(Object incomingJson) {
        var convertedArgs = Arrays.stream(new Object[] { incomingJson })
            .map(o -> this.polyglotContext.asValue(o)).toArray();
        var result = mainTransformFunction.execute(convertedArgs);
        log.atTrace().setMessage("result={}").addArgument(result).log();
        return valueToJavaObject(result);
    }

    /** Access the polyglot context for subclass-specific operations. */
    protected Context getPolyglotContext() {
        return polyglotContext;
    }

    /** Access the main transform function for subclass-specific operations (e.g. Promise handling). */
    protected Value getMainTransformFunction() {
        return mainTransformFunction;
    }

    @Override
    public void close() throws Exception {
        this.mainTransformFunction = null;
        this.polyglotContext.close();
        this.infoStream.close();
        this.errorStream.close();
    }

    static Object valueToJavaObject(Value val) {
        if (val.isHostObject()) {
            return val.asHostObject();
        } else if (val.isProxyObject()) {
            return val.asProxyObject();
        } else {
            return val.as(Object.class);
        }
    }

    /**
     * OutputStream that redirects writes to an SLF4J logger at a given level.
     * Flushes accumulated bytes as a single log message.
     */
    public static class LoggingOutputStream extends FilterOutputStream {
        private final Logger logger;
        private final Level level;

        public LoggingOutputStream(Logger logger, Level level) {
            super(new ByteArrayOutputStream());
            this.logger = logger;
            this.level = level;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            super.flush();
            if (out instanceof ByteArrayOutputStream) {
                var baos = (ByteArrayOutputStream) out;
                out.flush();
                if (baos.size() > 0) {
                    logger.atLevel(level).setMessage("{}")
                        .addArgument(() -> baos.toString(StandardCharsets.UTF_8))
                        .log();
                    baos.reset();
                }
            }
        }
    }
}
