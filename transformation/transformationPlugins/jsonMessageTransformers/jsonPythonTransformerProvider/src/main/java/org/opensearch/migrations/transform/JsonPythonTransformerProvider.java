package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

@Slf4j
public class JsonPythonTransformerProvider extends ScriptTransformerProvider {

    public static final String PYTHON_MODULE_PATH_KEY = "pythonModulePath";

    @Override
    protected String getLanguageName() {
        return "Python";
    }

    @Override
    protected IJsonTransformer buildTransformer(
            ResolvedScript script, Object bindingsObject, Map<String, Object> config) throws IOException {
        var venvPath = resolveVenvPath(config);
        var scriptParentPath = script.sourceFile() == null ? null : script.sourceFile().getParent();
        return new PythonTransformer(script.source(), bindingsObject, venvPath, scriptParentPath);
    }

    @Override
    protected String getConfigUsageStr() {
        return super.getConfigUsageStr() + "\n"
            + PYTHON_MODULE_PATH_KEY
            + " (optional) is a local directory path or .tar.gz file containing a Python venv with pip packages.";
    }

    private Path resolveVenvPath(Map<String, Object> config) throws IOException {
        var modulePath = (String) config.getOrDefault(PYTHON_MODULE_PATH_KEY, null);
        if (modulePath == null) {
            return null;
        }
        var localPath = Path.of(modulePath);
        if (Files.isDirectory(localPath)) {
            return localPath;
        }
        var pathStr = localPath.toString();
        if (Files.isRegularFile(localPath) && (pathStr.endsWith(".tar.gz") || pathStr.endsWith(".tgz"))) {
            return extractTarGz(localPath);
        }
        throw new IllegalArgumentException(
            "pythonModulePath '" + modulePath + "' must be a directory or a .tar.gz file."
        );
    }

    static Path extractTarGz(Path tarGzPath) throws IOException {
        var extractDir = Files.createTempDirectory("python-venv-");
        log.atInfo().setMessage("Extracting {} to {}").addArgument(tarGzPath).addArgument(extractDir).log();
        try (var fis = Files.newInputStream(tarGzPath);
             var gis = new GzipCompressorInputStream(fis);
             var tis = new TarArchiveInputStream(gis)) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                var entryPath = extractDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(extractDir)) {
                    throw new IOException("Tar entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(tis, entryPath);
                }
            }
        }
        return findVenvRoot(extractDir);
    }

    /**
     * Find the GraalPy resources root — the directory that contains {@code venv/pyvenv.cfg}.
     * GraalPyResources.contextBuilder(Path) expects this directory and looks for {@code venv/}
     * underneath it.
     */
    static Path findVenvRoot(Path searchRoot) throws IOException {
        try (var stream = Files.walk(searchRoot)) {
            return stream
                .filter(p -> p.getFileName().toString().equals("pyvenv.cfg"))
                .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("venv"))
                .map(p -> p.getParent().getParent()) // venv/pyvenv.cfg -> parent of venv/
                .findFirst()
                .orElse(searchRoot);
        }
    }
}
