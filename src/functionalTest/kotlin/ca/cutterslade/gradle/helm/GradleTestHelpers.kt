package ca.cutterslade.gradle.helm

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.jetbrains.spek.api.dsl.SpecBody
import org.jetbrains.spek.api.dsl.TestBody
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

fun BuildResult.taskOutcome(name: String, outcome: TaskOutcome) =
    assert(assertNotNull(task(":$name")).outcome == outcome)

fun BuildResult.taskSuccess(name: String) = taskOutcome(name, TaskOutcome.SUCCESS)
fun BuildResult.taskUpToDate(name: String) = taskOutcome(name, TaskOutcome.UP_TO_DATE)
fun BuildResult.taskFailed(name: String) = taskOutcome(name, TaskOutcome.FAILED)

fun setupTaskBuild(dir: Path, task: String, vararg extraArgs: String) =
    GradleRunner.create()
        .withProjectDir(dir.toFile())
        .withArguments(task, "-s", *extraArgs)
        .withPluginClasspath()

fun buildTask(dir: Path, task: String, vararg extraArgs: String) =
    setupTaskBuild(dir, task, *extraArgs).build()

fun buildTaskForFailure(dir: Path, task: String, vararg extraArgs: String) =
    setupTaskBuild(dir, task, *extraArgs).buildAndFail()

fun SpecBody.successThenUpToDate(dir: Path, task: String, assertions: TestBody.() -> Unit = {}) {
  it("can $task") {
    buildTask(dir, task).run {
      taskSuccess(HelmPlugin.VERIFY_TASK_NAME)
      taskSuccess(task)
    }
    assertions()
  }
  it("can consider $task up-to-date") {
    buildTask(dir, task).run {
      taskSuccess(HelmPlugin.VERIFY_TASK_NAME)
      taskUpToDate(task)
    }
    assertions()
  }
}

internal fun Path.isDir(vararg subDir: String): Path {
  assertTrue(Files.isDirectory(this), "isDirectory($this); ${parentContents()}")
  return if (subDir.isNotEmpty()) resolve(subDir.first()).isDir(*(subDir.toList().subList(1,
      subDir.size).toTypedArray()))
  else this
}

internal fun Path.isFile(vararg subDirsFile: String): Path =
    if (subDirsFile.isEmpty()) also { assertTrue(Files.isRegularFile(this), "isFile($this); ${parentContents()}") }
    else if (subDirsFile.size == 1) resolve(subDirsFile[0]).also {
      assertTrue(Files.isRegularFile(it),
          "isFile($it); ${it.parentContents()}")
    }
    else isDir(*subDirsFile.toList().subList(0,
        subDirsFile.size - 1).toTypedArray()).isFile(subDirsFile[subDirsFile.size - 1])


internal fun Path.parentContents() = "$parent contains ${parent.toFile().list().contentToString()}"

internal fun TestBody.withModifiedFile(
    file: Path,
    transform: ((String) -> CharSequence),
    testAction: TestBody.() -> Unit
) {
  val original = file.toFile().readLines()
  file.toFile().writeText(original.joinToString(separator = "\n", transform = transform))
  try {
    testAction()
  }
  finally {
    file.toFile().writeText(original.joinToString(separator = "\n"))
  }
}

