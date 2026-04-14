package org.opensearch.migrations.transform;

import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.python.embedding.GraalPyResources;
import org.graalvm.python.embedding.VirtualFileSystem;

/**
 * Executes Python transformations using GraalPy.
 *
 * <p>When a {@code venvPath} is provided, uses that external venv for pip packages.
 * Otherwise uses a virtual filesystem with host read access for script file loading.
 */
@Slf4j
public class PythonTransformer extends GraalTransformer {
    private static final String LANGUAGE_ID = "python";

    public PythonTransformer(String script, Object bindings) {
        this(script, bindings, null);
    }

    public PythonTransformer(String script, Object bindings, Path venvPath) {
        super(LANGUAGE_ID, script, bindings, createContextBuilder(venvPath));
    }

    private static Context.Builder createContextBuilder(Path venvPath) {
        Context.Builder builder;
        if (venvPath != null) {
            log.atInfo().setMessage("Using external Python venv: {}").addArgument(venvPath).log();
            builder = GraalPyResources.contextBuilder(venvPath);
        } else {
            builder = GraalPyResources.contextBuilder(
                VirtualFileSystem.newBuilder()
                    .allowHostIO(VirtualFileSystem.HostIO.READ)
                    .build()
            );
        }
        return builder.option("engine.WarnInterpreterOnly", "false");
    }
}
