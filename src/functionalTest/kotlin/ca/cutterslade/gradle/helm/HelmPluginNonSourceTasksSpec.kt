package ca.cutterslade.gradle.helm

import com.google.common.io.MoreFiles
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.SpecBody
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull

class HelmPluginNonSourceTasksSpec : Spek({
  val projectDirectory: Path = Files.createTempDirectory(HelmPluginNonSourceTasksSpec::class.simpleName)
  projectDirectory.resolve("build.gradle").also {
    it.toFile().writeText("""
      plugins {
        id 'ca.cutterslade.helm'
      }
    """.trimIndent())
  }
  afterGroup {
    MoreFiles.deleteRecursively(projectDirectory)
  }

  fun BuildResult.taskOutcome(name: String, outcome: TaskOutcome) =
      assert(assertNotNull(task(":$name")).outcome == outcome)

  fun BuildResult.taskSuccess(name: String) = taskOutcome(name, TaskOutcome.SUCCESS)
  fun BuildResult.taskUpToDate(name: String) = taskOutcome(name, TaskOutcome.UP_TO_DATE)
  fun BuildResult.taskFailed(name: String) = taskOutcome(name, TaskOutcome.FAILED)

  fun buildTask(task: String) = GradleRunner.create()
      .withProjectDir(projectDirectory.toFile())
      .withArguments(task)
      .withPluginClasspath()
      .build()

  fun SpecBody.successThenUpToDate(task: String) {
    it("can $task") {
      buildTask(task).run {
        taskSuccess(HelmPlugin.VERIFY_TASK_NAME)
        taskSuccess(task)
      }
    }
    it("can consider $task up-to-date") {
      buildTask(task).run {
        taskSuccess(HelmPlugin.VERIFY_TASK_NAME)
        taskUpToDate(task)
      }
    }
  }

  describe("The helm plugin") {
    it("can be applied to a project") {
      GradleRunner.create()
          .withProjectDir(projectDirectory.toFile())
          .withPluginClasspath()
          .build()
    }
    it("can verify architecture") {
      GradleRunner.create()
          .withProjectDir(projectDirectory.toFile())
          .withArguments(HelmPlugin.VERIFY_ARCH_TASK_NAME)
          .withPluginClasspath()
          .build()
          .taskSuccess(HelmPlugin.VERIFY_ARCH_TASK_NAME)
    }
    it("can verify operating system") {
      GradleRunner.create()
          .withProjectDir(projectDirectory.toFile())
          .withArguments(HelmPlugin.VERIFY_OS_TASK_NAME)
          .withPluginClasspath()
          .build()
          .taskSuccess(HelmPlugin.VERIFY_OS_TASK_NAME)
    }
    it("can verify plugin support") {
      GradleRunner.create()
          .withProjectDir(projectDirectory.toFile())
          .withArguments(HelmPlugin.VERIFY_TASK_NAME)
          .withPluginClasspath()
          .build()
          .run {
            taskSuccess(HelmPlugin.VERIFY_ARCH_TASK_NAME)
            taskSuccess(HelmPlugin.VERIFY_OS_TASK_NAME)
            taskSuccess(HelmPlugin.VERIFY_TASK_NAME)
          }
    }
    it("can fail plugin support for unknown architecture") {
      GradleRunner.create()
          .withProjectDir(projectDirectory.toFile())
          .withArguments(HelmPlugin.VERIFY_TASK_NAME, "-Dos.arch=flips")
          .withPluginClasspath()
          .buildAndFail()
          .taskFailed(HelmPlugin.VERIFY_ARCH_TASK_NAME)
    }
    it("can fail plugin support for unknown architecture") {
      GradleRunner.create()
          .withProjectDir(projectDirectory.toFile())
          .withArguments(HelmPlugin.VERIFY_TASK_NAME, "-Dos.name=windoze")
          .withPluginClasspath()
          .buildAndFail()
          .taskFailed(HelmPlugin.VERIFY_OS_TASK_NAME)
    }

    successThenUpToDate(HelmPlugin.DOWNLOAD_TASK_NAME)
    successThenUpToDate(HelmPlugin.INSTALL_TASK_NAME)
    successThenUpToDate(HelmPlugin.INITIALIZE_TASK_NAME)
  }
})
