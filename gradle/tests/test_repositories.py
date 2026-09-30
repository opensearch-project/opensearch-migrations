#!/usr/bin/env python3
"""Exercise repository configuration with real Gradle and a local Maven server.

Run with JDK 21: python3 -m unittest discover -s gradle/tests -v
The Gradle wrapper distribution is reused; test dependencies never use public repositories.
"""

import base64
import functools
import http.server
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import zipfile


REPO = Path(__file__).resolve().parents[2]
CONFIG = REPO / "gradle/repositories.gradle"
WRAPPER = REPO / "gradlew"
MIRROR_ENV = (
    "MAVEN_REPOSITORY_URL", "MAVEN_REPOSITORY_USERNAME",
    "MAVEN_REPOSITORY_PASSWORD", "MAVEN_REPOSITORY_PASSWORD_FILE",
)


class RepositoryHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        self.server.requests.append((self.path, self.headers.get("Authorization")))
        if self.server.error_status:
            self.send_error(self.server.error_status, "Test repository unavailable")
        elif self.headers.get("Authorization") != self.server.authorization:
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="test"')
            self.end_headers()
        else:
            super().do_GET()

    def log_message(self, *_args):
        pass


class RepositoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="migrations-repositories-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.env = {key: value for key, value in os.environ.items() if key not in MIRROR_ENV}

        # Locate the wrapper's distribution once, then launch that distribution directly.
        # Each test gets an empty dependency cache without downloading Gradle repeatedly.
        bootstrap = cls.root / "bootstrap"
        bootstrap.mkdir()
        (bootstrap / "settings.gradle").write_text("rootProject.name = 'repository-test-bootstrap'\n")
        (bootstrap / "build.gradle").write_text("""
tasks.register('gradleHome') {
    doLast { println("GRADLE_HOME=" + gradle.gradleHomeDir) }
}
""")
        result = subprocess.run(
            [str(WRAPPER), "-p", str(bootstrap), "--no-daemon", "--console=plain", "-q", "gradleHome"],
            env=cls.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180,
        )
        if result.returncode:
            raise RuntimeError(result.stdout)
        gradle_home = Path(next(line.removeprefix("GRADLE_HOME=") for line in result.stdout.splitlines()
                                if line.startswith("GRADLE_HOME=")))
        cls.gradle = gradle_home / "bin/gradle"

        # Both resolution paths use real, valid artifacts served only by the test repository.
        source = cls.root / "Fixture.java"
        source.write_text("""
package test.dependencies;

public class Fixture {
    public static String value() { return "DEPENDENCY_OK"; }
}
""")
        plugin_source = cls.root / "RepositoryPlugin.java"
        plugin_source.write_text("""
package test.dependencies;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RepositoryPlugin implements Plugin<Project> {
    public void apply(Project project) {
        project.getTasks().register("mirrorPlugin", task ->
            task.doLast(ignored -> System.out.println("MIRROR_PLUGIN_OK")));
    }
}
""")
        classes = cls.root / "classes"
        classes.mkdir()
        subprocess.run(["javac", "-cp", str(gradle_home / "lib/*"), "-d", str(classes),
                        str(source), str(plugin_source)],
                       check=True, capture_output=True, text=True, timeout=60)
        cls.maven = cls.root / "maven"
        artifact = cls.maven / "test/dependencies/sample/1.0"
        artifact.mkdir(parents=True)
        (artifact / "sample-1.0.pom").write_text("""
<project><modelVersion>4.0.0</modelVersion>
<groupId>test.dependencies</groupId><artifactId>sample</artifactId><version>1.0</version></project>
""")
        with zipfile.ZipFile(artifact / "sample-1.0.jar", "w") as jar:
            jar.write(classes / "test/dependencies/Fixture.class", "test/dependencies/Fixture.class")
        plugin_artifact = cls.maven / "test/dependencies/plugin/1.0"
        plugin_artifact.mkdir(parents=True)
        (plugin_artifact / "plugin-1.0.pom").write_text("""
<project><modelVersion>4.0.0</modelVersion>
<groupId>test.dependencies</groupId><artifactId>plugin</artifactId><version>1.0</version></project>
""")
        with zipfile.ZipFile(plugin_artifact / "plugin-1.0.jar", "w") as jar:
            jar.write(classes / "test/dependencies/RepositoryPlugin.class", "test/dependencies/RepositoryPlugin.class")
            jar.writestr("META-INF/gradle-plugins/test.repository-check.properties",
                         "implementation-class=test.dependencies.RepositoryPlugin\n")
        marker = cls.maven / "test/repository-check/test.repository-check.gradle.plugin/1.0"
        marker.mkdir(parents=True)
        (marker / "test.repository-check.gradle.plugin-1.0.pom").write_text("""
<project><modelVersion>4.0.0</modelVersion>
<groupId>test.repository-check</groupId><artifactId>test.repository-check.gradle.plugin</artifactId>
<version>1.0</version><packaging>pom</packaging><dependencies><dependency>
<groupId>test.dependencies</groupId><artifactId>plugin</artifactId><version>1.0</version>
</dependency></dependencies></project>
""")

    def setUp(self):
        self.project = self.root / self.id().rsplit(".", 1)[-1]
        self.project.mkdir()
        self.server = http.server.ThreadingHTTPServer(
            ("127.0.0.1", 0), functools.partial(RepositoryHandler, directory=str(self.maven)))
        self.server.requests = []
        self.server.error_status = None
        self.username = "cache-reader"
        self.password = "test-token-must-not-be-logged"
        self.server.authorization = "Basic " + base64.b64encode(
            f"{self.username}:{self.password}".encode()).decode()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.environment = dict(self.env, MAVEN_REPOSITORY_URL=f"http://127.0.0.1:{self.server.server_port}/",
                                MAVEN_REPOSITORY_USERNAME=self.username, MAVEN_REPOSITORY_PASSWORD=self.password)

    def settings(self, directory):
        (directory / "settings.gradle").write_text(f"""
pluginManagement {{
    settings.apply from: '{CONFIG}'
    settings.ext.configureMavenRepositories(repositories, true)
    // Plain HTTP is permitted only for this loopback test server.
    repositories.withType(MavenArtifactRepository).configureEach {{ repository ->
        repository.allowInsecureProtocol = true
    }}
}}
rootProject.name = '{directory.name}'
""")

    def repositories(self):
        return f"""
apply from: '{CONFIG}'
configureMavenRepositories(repositories, false)
repositories.withType(MavenArtifactRepository).configureEach {{ repository ->
    repository.allowInsecureProtocol = true
}}
"""

    def run_gradle(self, task, success=True):
        result = subprocess.run(
            [str(self.gradle), "-p", str(self.project), "-g", str(self.project / "gradle-home"),
             "--no-daemon", "--console=plain", "--max-workers=2", task],
            env=self.environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180,
        )
        self.assertNotIn(self.password, result.stdout)
        self.assertEqual(result.returncode == 0, success, result.stdout)
        return result.stdout

    def test_plugin_buildsrc_and_detached_dependencies_use_authenticated_cache(self):
        self.check_authenticated_resolution()

    def test_file_credentials_authenticate_plugins_buildsrc_and_detached_dependencies(self):
        password_file = self.project / "private-token"
        password_file.write_text(self.password + "\n")
        self.environment.pop("MAVEN_REPOSITORY_PASSWORD")
        self.environment["MAVEN_REPOSITORY_PASSWORD_FILE"] = str(password_file)
        self.check_authenticated_resolution()

    def check_authenticated_resolution(self):
        self.settings(self.project)
        build_src = self.project / "buildSrc"
        build_src.mkdir()
        self.settings(build_src)
        (build_src / "build.gradle").write_text(
            "plugins { id 'java-library' }\n" + self.repositories() + """
dependencies { implementation 'test.dependencies:sample:1.0' }
assert repositories.size() == 1
""")
        source = build_src / "src/main/java/check/BuildTime.java"
        source.parent.mkdir(parents=True)
        source.write_text("""
package check;
public class BuildTime {
    public static String value() { return test.dependencies.Fixture.value(); }
}
""")
        (self.project / "build.gradle").write_text("""
plugins { id 'test.repository-check' version '1.0' }
""" + self.repositories() + """
assert repositories.size() == 1
configurations { probe }
dependencies { probe 'test.dependencies:sample:1.0' }
tasks.register('resolveDependencies') {
    dependsOn 'mirrorPlugin'
    doLast {
        assert configurations.probe.singleFile.name == 'sample-1.0.jar'
        def detached = configurations.detachedConfiguration(dependencies.create('test.dependencies:sample:1.0'))
        assert detached.singleFile.name == 'sample-1.0.jar'
        println("BUILDSRC_" + check.BuildTime.value())
        println("NORMAL_AND_DETACHED_OK")
    }
}
""")
        output = self.run_gradle("resolveDependencies")
        for expected in ("MIRROR_PLUGIN_OK", "BUILDSRC_DEPENDENCY_OK", "NORMAL_AND_DETACHED_OK"):
            self.assertIn(expected, output)
        paths = [path for path, _ in self.server.requests]
        self.assertTrue(any("test.repository-check.gradle.plugin-1.0.pom" in path for path in paths), paths)
        self.assertTrue(any(path.endswith("sample-1.0.jar") for path in paths), paths)
        self.assertTrue(all(auth == self.server.authorization for _, auth in self.server.requests),
                        self.server.requests)

    def test_defaults_preserve_public_repositories(self):
        self.environment = dict(self.env)
        self.settings(self.project)
        with (self.project / "settings.gradle").open("a") as settings:
            settings.write("""
assert pluginManagement.repositories*.url*.toString() == ['https://plugins.gradle.org/m2']
""")
        (self.project / "build.gradle").write_text(self.repositories() + """
tasks.register('reportRepositories') {
    doLast {
        assert repositories*.url*.toString() == ['https://repo.maven.apache.org/maven2/']
        println('PUBLIC_DEFAULTS_OK')
    }
}
""")
        self.assertIn("PUBLIC_DEFAULTS_OK", self.run_gradle("reportRepositories"))
        self.assertEqual(self.server.requests, [])

    def test_cache_failure_does_not_fall_back_to_public_repositories(self):
        self.server.error_status = 429
        self.settings(self.project)
        (self.project / "build.gradle").write_text(self.repositories() + """
assert repositories.size() == 1
configurations { probe }
dependencies { probe 'test.dependencies:sample:1.0' }
tasks.register('resolveDependencies') {
    doLast { configurations.probe.resolve() }
}
""")
        output = self.run_gradle("resolveDependencies", success=False)
        self.assertIn("429", output)
        self.assertNotIn("repo.maven.apache.org", output)
        self.assertNotIn("plugins.gradle.org", output)
        self.assertTrue(self.server.requests)

    def test_anonymous_repository_can_resolve_dependencies(self):
        self.server.authorization = None
        self.environment.pop("MAVEN_REPOSITORY_USERNAME")
        self.environment.pop("MAVEN_REPOSITORY_PASSWORD")
        self.settings(self.project)
        (self.project / "build.gradle").write_text(self.repositories() + """
configurations { probe }
dependencies { probe 'test.dependencies:sample:1.0' }
tasks.register('resolveDependencies') {
    doLast { assert configurations.probe.singleFile.name == 'sample-1.0.jar' }
}
""")
        self.run_gradle("resolveDependencies")
        self.assertTrue(self.server.requests)
        self.assertTrue(all(auth is None for _, auth in self.server.requests), self.server.requests)

    def test_partial_credentials_fail_before_network_access(self):
        self.environment.pop("MAVEN_REPOSITORY_USERNAME")
        self.settings(self.project)
        (self.project / "build.gradle").write_text("")
        output = self.run_gradle("help", success=False)
        self.assertIn("Set MAVEN_REPOSITORY_USERNAME and one password source, or neither", output)
        self.assertEqual(self.server.requests, [])

    def test_multiple_password_sources_fail_before_network_access(self):
        self.environment["MAVEN_REPOSITORY_PASSWORD_FILE"] = str(self.project / "private-token")
        self.settings(self.project)
        (self.project / "build.gradle").write_text("")
        output = self.run_gradle("help", success=False)
        self.assertIn("Set only one of MAVEN_REPOSITORY_PASSWORD and MAVEN_REPOSITORY_PASSWORD_FILE", output)
        self.assertEqual(self.server.requests, [])

    def test_missing_password_file_fails_without_logging_its_path(self):
        self.environment.pop("MAVEN_REPOSITORY_PASSWORD")
        self.environment["MAVEN_REPOSITORY_PASSWORD_FILE"] = str(self.project / self.password)
        self.settings(self.project)
        (self.project / "build.gradle").write_text("")
        output = self.run_gradle("help", success=False)
        self.assertIn("MAVEN_REPOSITORY_PASSWORD_FILE must name a readable, nonempty file", output)
        self.assertEqual(self.server.requests, [])

    def test_empty_password_file_fails_before_network_access(self):
        password_file = self.project / "private-token"
        password_file.write_text("\n")
        self.environment.pop("MAVEN_REPOSITORY_PASSWORD")
        self.environment["MAVEN_REPOSITORY_PASSWORD_FILE"] = str(password_file)
        self.settings(self.project)
        (self.project / "build.gradle").write_text("")
        output = self.run_gradle("help", success=False)
        self.assertIn("MAVEN_REPOSITORY_PASSWORD_FILE must name a readable, nonempty file", output)
        self.assertEqual(self.server.requests, [])

    def test_credentials_in_url_are_rejected_without_logging_them(self):
        self.environment["MAVEN_REPOSITORY_URL"] = f"https://user:{self.password}@example.invalid/"
        self.settings(self.project)
        (self.project / "build.gradle").write_text("")
        output = self.run_gradle("help", success=False)
        self.assertIn("MAVEN_REPOSITORY_URL", output)
        self.assertEqual(self.server.requests, [])


if __name__ == "__main__":
    unittest.main()
