package ca.cutterslade.gradle.helm

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.jetbrains.spek.api.dsl.SpecBody
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Path
import kotlin.test.assertNotNull

fun BuildResult.taskOutcome(name: String, outcome: TaskOutcome) =
    assert(assertNotNull(task(":$name")).outcome == outcome)

fun BuildResult.taskSuccess(name: String) = taskOutcome(name, TaskOutcome.SUCCESS)
fun BuildResult.taskUpToDate(name: String) = taskOutcome(name, TaskOutcome.UP_TO_DATE)
fun BuildResult.taskFailed(name: String) = taskOutcome(name, TaskOutcome.FAILED)

fun setupTaskBuild(dir: Path, task: String, vararg extraArgs: String) =
    GradleRunner.create()
        .withProjectDir(dir.toFile())
        .withArguments(task, *extraArgs)
        .withPluginClasspath()

fun buildTask(dir: Path, task: String, vararg extraArgs: String) =
    setupTaskBuild(dir, task, *extraArgs).build()

fun buildTaskForFailure(dir: Path, task: String, vararg extraArgs: String) =
    setupTaskBuild(dir, task, *extraArgs).buildAndFail()

fun SpecBody.successThenUpToDate(dir: Path, task: String) {
  it("can $task") {
    buildTask(dir, task).run {
      taskSuccess(HelmPlugin.VERIFY_TASK_NAME)
      taskSuccess(task)
    }
  }
  it("can consider $task up-to-date") {
    buildTask(dir, task).run {
      taskSuccess(HelmPlugin.VERIFY_TASK_NAME)
      taskUpToDate(task)
    }
  }
}

