package ca.cutterslade.gradle.helm

import com.google.common.io.MoreFiles
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

object HelmPluginSimpleChartSpec : Spek({
  val projectName = "create-helm-chart-functional-test-build"
  val projectDirectory: Path = Files.createTempDirectory(HelmPluginSimpleChartSpec::class.simpleName)
  val buildFile = projectDirectory.resolve("build.gradle")
  val settingsFile = projectDirectory.resolve("settings.gradle")

  beforeEachTest {
    buildFile.toFile().writeText("""
      plugins { id 'ca.cutterslade.helm' }
      version = '0.1.0'
      """.trimIndent())
    settingsFile.toFile().writeText("rootProject.name = '$projectName'\n")
  }

  afterGroup {
    MoreFiles.deleteRecursively(projectDirectory)
  }

  describe("The helm plugin") {
    it("can create a chart") {
      buildTask(projectDirectory, HelmPlugin.CREATE_TASK_NAME).run {
        taskSuccess(HelmPlugin.ENSURE_NO_CHART_TASK_NAME)
        taskSuccess(HelmPlugin.INITIALIZE_TASK_NAME)
        taskSuccess(HelmPlugin.CREATE_TASK_NAME)
      }

      projectDirectory.isDir("build", "helm", "install")
      projectDirectory.isDir("build", "helm", "home")
      projectDirectory.isFile("src", "helm", "resources", projectName, "Chart.yaml").run {
        assertTrue(Files.lines(this).anyMatch { Regex("name: \\Q$projectName\\E").matches(it) },
            "Chart.yaml file missing expected line 'name: $projectName'")
      }
    }
    it("fails to create a chart in an existing directory") {
      buildTaskForFailure(projectDirectory, HelmPlugin.CREATE_TASK_NAME).run {
        taskFailed(HelmPlugin.ENSURE_NO_CHART_TASK_NAME)
        assertTrue(output.contains("Cannot create chart when exists: '"))
      }
    }
    it("validates a chart") {
      buildTask(projectDirectory, HelmPlugin.LINT_TASK_NAME).run {
        taskSuccess(HelmPlugin.LINT_TASK_NAME)
      }
    }
    it("fails validation of a chart with non-semantic version") {
      withModifiedFile(
          projectDirectory.resolve("src/helm/resources/$projectName/Chart.yaml"),
          { if (it.startsWith("version:")) "" else it },
          {
            buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME).run {
              taskFailed(HelmPlugin.LINT_TASK_NAME)
              assertTrue(output.contains("[ERROR] Chart.yaml: version is required"), "Expected version required error")
            }
          }
      )
    }
    it("fails validation of a chart with name not matching directory") {
      withModifiedFile(
          projectDirectory.resolve("src/helm/resources/$projectName/Chart.yaml"),
          { if (it.startsWith("name:")) "name: me" else it },
          {
            buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME).run {
              taskFailed(HelmPlugin.LINT_TASK_NAME)
              assertTrue(output.contains("[ERROR] Chart.yaml: directory name ("), "Expected directory name error")
            }
          }
      )
    }
    it("fails validation of a chart with malformed values.yaml") {
      withModifiedFile(
          projectDirectory.resolve("src/helm/resources/$projectName/values.yaml"),
          { if (it.startsWith("image:")) "image: {" else it },
          {
            buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME).run {
              taskFailed(HelmPlugin.LINT_TASK_NAME)
              assertTrue(output.contains("[ERROR] values.yaml: unable to parse YAML"),
                  "Expected unable to parse YAML error")
            }
          }
      )
    }

    successThenUpToDate(projectDirectory, HelmPlugin.PACKAGE_TASK_NAME) {
      projectDirectory.isFile("build", "helm", "package", "$projectName-0.1.0.tgz")
    }
  }
})
