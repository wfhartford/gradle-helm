package ca.cutterslade.gradle.helm

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.jetbrains.spek.api.dsl.SpecBody
import org.jetbrains.spek.api.dsl.TestBody
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

fun BuildResult.taskOutcome(name: String, outcome: TaskOutcome) =
    assertEquals(outcome, assertNotNull(task(":$name"), "Task $name").outcome, "Task $name")

fun BuildResult.taskSuccess(name: String) = taskOutcome(name, TaskOutcome.SUCCESS)
fun BuildResult.taskSuccessOrUpToDate(name: String) {
  val task = assertNotNull(task(":$name"), "Task $name")
  if (task.outcome !in listOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE)) {
    fail("Task $name outcome was ${task.outcome}, expected SUCCESS or UP_TO_DATE")
  }
}

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
      taskSuccess(task)
    }
    assertions()
  }
  it("can consider $task up-to-date") {
    buildTask(dir, task).run {
      taskUpToDate(task)
    }
    assertions()
  }
}

fun SpecBody.doubleSuccess(dir: Path, task: String, assertions: TestBody.() -> Unit = {}) {
  it("can $task") {
    buildTask(dir, task).run {
      taskSuccess(task)
    }
    assertions()
  }
  it("can $task a second time without considering it up-to-date") {
    buildTask(dir, task).run {
      taskSuccess(task)
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
    transform: ((List<String>) -> List<String>),
    testAction: TestBody.() -> Unit
) {
  val original = file.toFile().readLines()
  val modified = transform(original)
  assertNotEquals(original, modified, "withModifiedFile did not modify file")
  file.toFile().writeText(modified.joinToString(separator = "\n"))
  try {
    testAction()
  }
  finally {
    file.toFile().writeText(original.joinToString(separator = "\n"))
  }
}

internal fun removeLine(line: Regex): (List<String>) -> List<String> = { lines ->
  lines.mapNotNull { if (it.matches(line)) null else it }
}

internal fun replaceLine(line: Regex, replace: String): (List<String>) -> List<String> = { lines ->
  lines.map { if (it.matches(line)) replace else it }
}

internal fun repositoryTransform(
    server: GrizzlyServer,
    username: String? = "user",
    password: String? = "pass",
    realm: String? = null,
    type: String? = null
): (List<String>) -> List<String> = { lines ->
  lines.flatMap {
    if (it.contains("repository")) {
      listOf(
          "  repository {",
          "    url 'http://localhost:${server.port}'",
          type?.let { " type '$type'" },
          username?.let { "    username '$username'" },
          password?.let { "    password '$password'" },
          realm?.let { "    authRealm '$realm'" },
          "  }"
      ).filterNotNull()
    }
    else {
      listOf(it)
    }
  }
}
