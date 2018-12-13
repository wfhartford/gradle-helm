package ca.cutterslade.gradle.helm

import com.google.common.io.MoreFiles
import com.google.common.io.RecursiveDeleteOption
import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.fail

object HelmPluginNonSourceTasksSpec : Spek({
  val projectDirectory: Path = Files.createTempDirectory(HelmPluginNonSourceTasksSpec::class.simpleName)
  val buildFile = projectDirectory.resolve("build.gradle").also {
    it.toFile().writeText("plugins { id 'ca.cutterslade.helm' }")
  }
  afterGroup {
    MoreFiles.deleteRecursively(projectDirectory, RecursiveDeleteOption.ALLOW_INSECURE)
  }

  describe("The helm plugin") {

    it("can be applied to a project") {
      GradleRunner.create()
          .withProjectDir(projectDirectory.toFile())
          .withPluginClasspath()
          .build().run {
            HelmPlugin.CONSTANT_TASKS_NAMES.forEach { taskName ->
              val executed = tasks.filter { it.path == ":$taskName" }
              assertEquals(listOf(), executed, "Task '$taskName' should not have executed")
            }
            HelmPlugin.VARIABLE_TASK_NAME_REGEXES.forEach { taskNameRegex ->
              val executed = tasks.filter { taskNameRegex.containsMatchIn(it.path) }
              assertEquals(listOf(), executed, "Tasks matching '$taskNameRegex' should not have executed")
            }
          }
    }
    it("creates static tasks") {
      buildTask(projectDirectory, "tasks").run {
        taskSuccess("tasks")
        val lines = output.lines()
        val startHelmTasks = lines.indexOfFirst { it == "Helm tasks" }
        val endHelmTasks = startHelmTasks + lines.subList(startHelmTasks, lines.size).indexOfFirst { it.isEmpty() }
        val helmTasks = lines.subList(startHelmTasks + 2, endHelmTasks)
            .map { it.subSequence(0, it.indexOf(' ')) }
            .toSet()
        assertEquals(HelmPlugin.CONSTANT_TASKS_NAMES, helmTasks, "Unexpected list of helm tasks")
      }
    }
    it("can verify architecture") {
      buildTask(projectDirectory, HelmPlugin.VERIFY_ARCH_TASK_NAME)
          .taskSuccess(HelmPlugin.VERIFY_ARCH_TASK_NAME)
    }
    it("can verify operating system") {
      buildTask(projectDirectory, HelmPlugin.VERIFY_OS_TASK_NAME)
          .taskSuccess(HelmPlugin.VERIFY_OS_TASK_NAME)
    }
    it("can verify plugin support") {
      buildTask(projectDirectory, HelmPlugin.VERIFY_TASK_NAME).run {
        taskSuccess(HelmPlugin.VERIFY_ARCH_TASK_NAME)
        taskSuccess(HelmPlugin.VERIFY_OS_TASK_NAME)
        taskSuccess(HelmPlugin.VERIFY_TASK_NAME)
      }
    }
    it("can fail plugin support for unknown architecture") {
      buildTaskForFailure(projectDirectory, HelmPlugin.VERIFY_TASK_NAME, "-Dos.arch=flips")
          .taskFailed(HelmPlugin.VERIFY_ARCH_TASK_NAME)
    }
    it("can fail plugin support for unknown architecture") {
      buildTaskForFailure(projectDirectory, HelmPlugin.VERIFY_TASK_NAME, "-Dos.name=windoze")
          .taskFailed(HelmPlugin.VERIFY_OS_TASK_NAME)
    }

    successThenUpToDate(projectDirectory, HelmPlugin.DOWNLOAD_TASK_NAME)
    successThenUpToDate(projectDirectory, HelmPlugin.INSTALL_TASK_NAME) {
      projectDirectory.isDir("build", "helm", "install")
    }
    successThenUpToDate(projectDirectory, HelmPlugin.INITIALIZE_TASK_NAME) {
      projectDirectory.isDir("build", "helm", "home", "cache")
      projectDirectory.isDir("build", "helm", "home", "plugins")
      projectDirectory.isDir("build", "helm", "home", "repository")
      projectDirectory.isDir("build", "helm", "home", "starters")
    }
    successThenUpToDate(projectDirectory, HelmPlugin.GET_VERSION_TASK_NAME)
    it("can ${HelmPlugin.CHECK_VERSION_TASK_NAME}") {
      buildTask(projectDirectory, HelmPlugin.CHECK_VERSION_TASK_NAME).run {
        taskSuccess(HelmPlugin.CHECK_VERSION_TASK_NAME)
      }
    }

    it("installs configured helm version") {
      files(
          ModifiedFile(
              buildFile,
              addLineFollowing("plugins .*", "helm { install { version = 'v2.11.0' } }")
          )
      ) {
        buildTask(projectDirectory, HelmPlugin.CHECK_VERSION_TASK_NAME)
            .taskSuccess(HelmPlugin.CHECK_VERSION_TASK_NAME)
      }
    }

    it("generates the helm source set") {
      files(
          ModifiedFile(
              buildFile,
              addLineFollowing("plugins .*", "task listSourceSets { doLast { println \"SOURCE-SETS: \$sourceSets\" } }")
          )
      ) {
        buildTask(projectDirectory, "listSourceSets").run {
          taskSuccess("listSourceSets")
          output.lines().find { it.startsWith("SOURCE-SETS: [") }?.let {
            assertEquals("SOURCE-SETS: [source set 'helm']", it, "Unexpected source sets output")
          } ?: fail("Could not find expected SOURCE-SETS line in output: \n$output")
        }
      }
    }

    it("generates the helm source set having the expected resource directory") {
      files(
          ModifiedFile(
              buildFile,
              addLineFollowing("plugins .*",
                  "task listHelmResourceDirs { doLast { println \"HELM-RESOURCES: \${sourceSets.helm.resources.srcDirs}\" } }")
          )
      ) {
        buildTask(projectDirectory, "listHelmResourceDirs").run {
          taskSuccess("listHelmResourceDirs")
          output.lines().find { it.startsWith("HELM-RESOURCES: [") }?.let {
            assertEquals(
                "HELM-RESOURCES: [${projectDirectory.resolve("src/main/helm")}]", it,
                "Unexpected resource directories")
          } ?: fail("Could not find expected HELM-RESOURCES line in output: \n$output")
        }
      }
    }

    it("generates the helm source set having the expected resource output directory") {
      files(
          ModifiedFile(
              buildFile,
              addLineFollowing("plugins .*",
                  "task listHelmOutputDirs { doLast { println \"HELM-OUTPUT: \${sourceSets.helm.output.resourcesDir}\" } }")
          )
      ) {
        buildTask(projectDirectory, "listHelmOutputDirs").run {
          taskSuccess("listHelmOutputDirs")
          output.lines().find { it.startsWith("HELM-OUTPUT: ") }?.let {
            assertEquals(
                "HELM-OUTPUT: ${projectDirectory.resolve("build/resources/helm")}", it,
                "Unexpected resource output directories")
          } ?: fail("Could not find expected HELM-OUTPUT line in output: \n$output")
        }
      }
    }

    it("generates the helm source set having no java source directories") {
      files(
          ModifiedFile(
              buildFile,
              addLineFollowing("plugins .*",
                  "task listHelmJavaDirs { doLast { println \"HELM-JAVA: \${sourceSets.helm.java.srcDirs}\" } }")
          )
      ) {
        buildTask(projectDirectory, "listHelmJavaDirs").run {
          taskSuccess("listHelmJavaDirs")
          output.lines().find { it.startsWith("HELM-JAVA: [") }?.let {
            assertEquals(
                "HELM-JAVA: []", it,
                "Unexpected java directories")
          } ?: fail("Could not find expected HELM-JAVA line in output: \n$output")
        }
      }
    }
  }
})
