package org.opensearch.migrations.transform;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Executes Python transformations on JSON-like objects using the GraalVM Polyglot API (GraalPy).
 *
 * <p><strong>Not thread-safe.</strong> Each thread should use its own instance.
 */
@Slf4j
public class PythonTransformer implements IJsonTransformer {
    private static final String PYTHON_TRANSFORM_LOGGER_NAME = "PythonTransformer";
    private Value mainPythonTransformFunction;

    private final Context polyglotContext;
    private final OutputStream infoStream;
    private final OutputStream errorStream;

    public PythonTransformer(String script, Object context) {
        var sourceCode = Source.create("python", script);
        var engine = Engine.newBuilder("python")
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        var builder = Context.newBuilder("python")
            .engine(engine)
            .allowHostAccess(HostAccess.newBuilder()
                .allowAccessAnnotatedBy(HostAccess.Export.class)
                .allowArrayAccess(true)
                .allowMapAccess(true)
                .allowListAccess(true)
                .allowIterableAccess(true)
                .allowBufferAccess(true)
                .build());
        var pyLogger = LoggerFactory.getLogger(PYTHON_TRANSFORM_LOGGER_NAME);
        this.infoStream = new LoggingOutputStream(pyLogger, Level.INFO);
        this.errorStream = new LoggingOutputStream(pyLogger, Level.ERROR);
        this.polyglotContext = builder
            .out(infoStream)
            .err(errorStream)
            .build();
        var sourceCodeValue = this.polyglotContext.eval(sourceCode);
        if (context != null) {
            var convertedContextObject = this.polyglotContext.asValue(context);
            this.mainPythonTransformFunction = sourceCodeValue.execute(convertedContextObject);
        } else {
            this.mainPythonTransformFunction = sourceCodeValue;
        }
    }

    @Override
    public void close() throws Exception {
        this.mainPythonTransformFunction = null;
        this.polyglotContext.close();
        this.infoStream.close();
        this.errorStream.close();
    }

    @Override
    public Object transformJson(Object incomingJson) {
        var convertedArgs = Arrays.stream(new Object[]{incomingJson})
            .map(o -> this.polyglotContext.asValue(o)).toArray();
        var result = mainPythonTransformFunction.execute(convertedArgs);
        log.atTrace().setMessage("result={}").addArgument(result).log();
        return valueToJavaObject(result);
    }

    private static Object valueToJavaObject(Value val) {
        if (val.isHostObject()) {
            return val.asHostObject();
        } else if (val.isProxyObject()) {
            return val.asProxyObject();
        } else {
            return val.as(Object.class);
        }
    }

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
