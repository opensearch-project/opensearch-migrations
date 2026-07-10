package org.opensearch.migrations.common

import org.gradle.api.Project
import org.gradle.api.tasks.Sync

class CommonUtils {

    static def syncVersionFileToDockerStaging(Project project, String destProjectName, String destDir) {
        return project.tasks.register("syncVersionFile_${destProjectName}", Sync) {
            from(project.rootProject.layout.projectDirectory.file("VERSION"))
            into(project.layout.projectDirectory.dir(destDir))
        }
    }
}
