package ca.cutterslade.gradle.helm

import com.google.common.collect.Sets
import com.google.common.io.MoreFiles
import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

object HelmPluginNonSourceTasksSpec : Spek({
  val projectDirectory: Path = Files.createTempDirectory(HelmPluginNonSourceTasksSpec::class.simpleName)
  projectDirectory.resolve("build.gradle").also {
    it.toFile().writeText("plugins { id 'ca.cutterslade.helm' }".trimIndent())
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
            assertEquals(
                setOf<String>(),
                Sets.intersection(
                    HelmPlugin.TASKS_NAMES.map { ":$it" }.toSet(),
                    tasks.map { it.path }.toSet()
                ),
                "No plugin tasks should have executed."
            )
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
  }
})
