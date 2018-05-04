package ca.cutterslade.gradle.helm

import com.google.common.io.MoreFiles
import com.google.common.io.RecursiveDeleteOption
import mu.KotlinLogging
import org.glassfish.grizzly.http.Method
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

object HelmPluginSimpleChartSpec : Spek({
  val logger = KotlinLogging.logger {}

  val projectName = "create-helm-chart-functional-test-build"
  val projectVersion = "0.1.0"
  val projectDirectory: Path = Files.createTempDirectory(HelmPluginSimpleChartSpec::class.simpleName)
  val buildFile = projectDirectory.resolve("build.gradle")
  val settingsFile = projectDirectory.resolve("settings.gradle")
  var _server: GrizzlyServer? = null
  fun server() = _server ?: throw IllegalStateException("_server is null")

  data class ChartBasics(val name: String, val version: String)

  val charts = listOf(
      ChartBasics(projectName, projectVersion),
      ChartBasics("other-test-chart", "1.0.0")
  )

  beforeGroup {
    _server = GrizzlyServer()
    server().start()
  }

  beforeEachTest {
    buildFile.toFile().writeText("""
      |plugins { id 'ca.cutterslade.helm' }
      |version = '$projectVersion'
      |helm {
      |  repository { url 'http://localhost:${server().port}' }
      |}
      |charts {
      |${charts.joinToString(
        separator = "\n",
        transform = { "  '${it.name}' {\n    chartVersion = '${it.version}'\n  }" }
    )}
      |}
      """.trimMargin())
    settingsFile.toFile().writeText("rootProject.name = '$projectName'\n")
  }

  afterEachTest {
    server().handler.reset()
  }

  afterGroup {
    server().close()
    _server = null
    try {
      MoreFiles.deleteRecursively(projectDirectory, RecursiveDeleteOption.ALLOW_INSECURE)
    } catch (e: FileSystemException) {
      logger.warn(e) { "Unable to delete created test directory... this is a common issue on Windows because of it's file locking protocols." }
    }
  }

  fun String.task(chart: ChartBasics) = HelmPlugin.chartTaskName(this, chart.name)

  describe("The helm plugin") {
    it("generates all tasks for each chart") {
      buildTask(projectDirectory, "tasks").run {
        HelmPlugin.CONSTANT_TASKS_NAMES.forEach {
          assertTrue(output.contains(it), "list of tasks includes $it")
        }
        HelmPlugin.VARIABLE_TASK_NAME_FORMATS.forEach { format ->
          charts.forEach { format.task(it).let { assertTrue(output.contains(it), "list of tasks includes $it") } }
        }
      }
    }

    charts.forEach { chart ->
      group("chart named ${chart.name}") {
        doubleSuccess(projectDirectory, HelmPlugin.ENSURE_NO_CHART_TASK_NAME_FORMAT.task(chart))

        it("can create a chart") {
          buildTask(projectDirectory, HelmPlugin.CREATE_TASK_NAME_FORMAT.task(chart)).run {
            taskSuccess(HelmPlugin.ENSURE_NO_CHART_TASK_NAME_FORMAT.task(chart))
            taskSuccessOrUpToDate(HelmPlugin.INITIALIZE_TASK_NAME)
            taskSuccess(HelmPlugin.CREATE_TASK_NAME_FORMAT.task(chart))
          }

          projectDirectory.isDir("build", "helm", "install")
          projectDirectory.isDir("build", "helm", "home")
          projectDirectory.isFile("src", "main", "helm", chart.name, "Chart.yaml").run {
            assertTrue(Files.lines(this).anyMatch { Regex("name: \\Q${chart.name}\\E").matches(it) },
                "Chart.yaml file missing expected line 'name: ${chart.name}'")
          }
        }

        it("fails to create a chart in an existing directory") {
          buildTaskForFailure(projectDirectory, HelmPlugin.CREATE_TASK_NAME_FORMAT.task(chart)).run {
            taskFailed(HelmPlugin.ENSURE_NO_CHART_TASK_NAME_FORMAT.task(chart))
            assertTrue(output.contains("Cannot create chart when exists: '"))
          }
        }

        it("validates a chart") {
          buildTask(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
            taskSuccess(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
          }
        }

        it("fails validation a chart in strict mode") {
          files(
              ModifiedFile(buildFile, addLineFollowing(Regex(".*\\Q'${chart.name}'\\E \\{.*"), "lint.strict = true")),
              {
                buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
                  taskFailed(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
                  assertTrue(output.contains("[ERROR] templates/: render error in "),
                      "Expected render error message")
                }
              }
          )
        }

        it("fails validation of a chart with non-semantic version") {
          files(
              ModifiedFile(
                  projectDirectory.resolve("src/main/helm/${chart.name}/Chart.yaml"),
                  removeLine(Regex("version:.*"))
              ),
              {
                buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
                  taskFailed(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
                  assertTrue(output.contains("[ERROR] Chart.yaml: version is required"),
                      "Expected version required error")
                }
              }
          )
        }

        it("fails validation of a chart with name not matching directory") {
          files(
              ModifiedFile(
                  projectDirectory.resolve("src/main/helm/${chart.name}/Chart.yaml"),
                  replaceLine(Regex("name:.*"), "name: me")
              ),
              {
                buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
                  taskFailed(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
                  assertTrue(output.contains("[ERROR] Chart.yaml: directory name ("), "Expected directory name error")
                }
              }
          )
        }

        it("fails validation of a chart with malformed values.yaml") {
          files(
              ModifiedFile(
                  projectDirectory.resolve("src/main/helm/${chart.name}/values.yaml"),
                  replaceLine(Regex("image:.*"), "image: {")
              ),
              {
                buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
                  taskFailed(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
                  assertTrue(output.contains("[ERROR] values.yaml: unable to parse YAML"),
                      "Expected unable to parse YAML error")
                }
              }
          )
        }

        it("fails validation of a chart with malformed values.yaml") {
          files(
              ModifiedFile(
                  projectDirectory.resolve("src/main/helm/${chart.name}/values.yaml"),
                  replaceLine(Regex("image:.*"), "image: {")
              ),
              {
                buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
                  taskFailed(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
                  assertTrue(output.contains("[ERROR] values.yaml: unable to parse YAML"),
                      "Expected unable to parse YAML error")
                }
              }
          )
        }

        it("fails validation of a chart including a missing required value reference") {
          files(
              AdditionalFile(
                  projectDirectory.resolve("src/main/helm/${chart.name}/templates/extra.txt"),
                  "{{ required \"missing value must be specified\" .Values.missing }}"
              ),
              {
                buildTaskForFailure(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
                  taskFailed(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
                  assertTrue(output.contains("missing value must be specified"),
                      "Expected message from required directive")
                }
              }
          )
        }

        it("validates a chart with a required value when specified in build file") {
          files(
              ModifiedFile(
                  buildFile,
                  addLineFollowing(Regex(".*\\Q'${chart.name}'\\E \\{.*"), "lint.values = [missing:'hi']")
              ),
              AdditionalFile(
                  projectDirectory.resolve("src/main/helm/${chart.name}/templates/extra.txt"),
                  "{{ required \"missing value must be specified\" .Values.missing }}"
              ),
              {
                buildTask(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
                  taskSuccess(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
                }
              }
          )
        }

        it("validates a chart with a required value when specified in values file") {
          files(
              ModifiedFile(
                  buildFile,
                  addLineFollowing(Regex(".*\\Q'${chart.name}'\\E \\{.*"), "lint.valuesFiles = ['values.yaml']")
              ),
              AdditionalFile(projectDirectory.resolve("values.yaml"), "missing: hi"),
              AdditionalFile(
                  projectDirectory.resolve("src/main/helm/${chart.name}/templates/extra.txt"),
                  "{{ required \"missing value must be specified\" .Values.missing }}"
              ),
              {
                buildTask(projectDirectory, HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart)).run {
                  taskSuccess(HelmPlugin.LINT_TASK_NAME_FORMAT.task(chart))
                }
              }
          )
        }

        successThenUpToDate(projectDirectory, HelmPlugin.PACKAGE_TASK_NAME_FORMAT.task(chart)) {
          projectDirectory.isFile("build", "helm", "package", "${chart.name}-${chart.version}.tgz")
        }

        it("can publish a chart") {
          buildTask(projectDirectory, HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart)).run {
            taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME_FORMAT.task(chart))
            taskSuccess(HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart))
            server().handler.let {
              assertEquals(1, it.requests.size, "Published with a single request")
              assertEquals(Method.PUT, it.requests[0].method, "Publish request method")
              assertEquals("/${chart.name}-${chart.version}.tgz", it.requests[0].path, "Publish request path")
              projectDirectory.isFile("build", "helm", "package", "${chart.name}-${chart.version}.tgz").run {
                assertEquals(toFile().length(), it.requests[0].contentLength, "Published package size")
              }
            }
          }
        }

        it("can publish a chart to chart museum") {
          files(
              ModifiedFile(buildFile, repositoryTransform(server(), type = "chartmuseum")),
              {
                buildTask(projectDirectory, HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart)).run {
                  taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME_FORMAT.task(chart))
                  taskSuccess(HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart))
                  server().handler.let {
                    assertEquals(1, it.requests.size, "Published with a single request")
                    assertEquals(Method.POST, it.requests[0].method, "Publish request method")
                    assertEquals("/", it.requests[0].path, "Publish request path")
                    projectDirectory.isFile("build", "helm", "package", "${chart.name}-${chart.version}.tgz").run {
                      assertEquals(toFile().length(), it.requests[0].contentLength, "Published package size")
                    }
                  }
                }
              }
          )
        }

        it("cannot publish a chart if server requires authentication and none provided") {
          server().handler.requireAuth = true
          buildTaskForFailure(projectDirectory, HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart)).run {
            taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME_FORMAT.task(chart))
            taskFailed(HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart))
            assertTrue(output.contains("code=401, message=Unauthorized"),
                "Build output should contain 'Response : Unauthorized'")
          }
        }

        it("can publish a chart if server requires authentication and correct credentials provided no realm") {
          files(
              ModifiedFile(buildFile, repositoryTransform(server())),
              {
                server().handler.requireAuth = true
                buildTask(projectDirectory, HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart)).run {
                  taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME_FORMAT.task(chart))
                  taskSuccess(HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart))
                  server().handler.let {
                    assertEquals(2, it.requests.size, "Required two requests")
                    assertFalse(it.requests[0].hasHeader("Authorization"),
                        "First request has no authorization: ${it.requests[0]}")
                    assertTrue(it.requests[1].hasHeader("Authorization"),
                        "Second request has authorization: ${it.requests[1]}")
                    it.requests.forEachIndexed { index, request ->
                      assertEquals(Method.PUT, request.method, "Publish request[$index] method: $request")
                      assertEquals("/${chart.name}-${chart.version}.tgz",
                          request.path,
                          "Publish request[$index] path: $request")
                      projectDirectory.isFile("build", "helm", "package", "${chart.name}-${chart.version}.tgz").run {
                        assertEquals(toFile().length(),
                            request.contentLength,
                            "Published package size in request[$index]: $request")
                      }
                    }
                  }
                }
              }
          )
        }
        it("can publish a chart if server requires authentication and correct credentials provided with realm") {
          files(
              ModifiedFile(buildFile, repositoryTransform(server(), realm = "test")),
              {
                server().handler.requireAuth = true
                buildTask(projectDirectory, HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart)).run {
                  taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME_FORMAT.task(chart))
                  taskSuccess(HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart))
                  server().handler.let {
                    assertEquals(2, it.requests.size, "Required two requests")
                    assertFalse(it.requests[0].hasHeader("Authorization"),
                        "First request has no authorization: ${it.requests[0]}")
                    assertTrue(it.requests[1].hasHeader("Authorization"),
                        "Second request has authorization: ${it.requests[1]}")
                    it.requests.forEachIndexed { index, request ->
                      assertEquals(Method.PUT, request.method, "Publish request[$index] method: $request")
                      assertEquals("/${chart.name}-${chart.version}.tgz",
                          request.path,
                          "Publish request[$index] path: $request")
                      projectDirectory.isFile("build", "helm", "package", "${chart.name}-${chart.version}.tgz").run {
                        assertEquals(toFile().length(),
                            request.contentLength,
                            "Published package size in request[$index]: $request")
                      }
                    }
                  }
                }
              }
          )
        }
        it("cannot publish a chart if server requires authentication and correct credentials provided with wrong realm") {
          files(
              ModifiedFile(buildFile, repositoryTransform(server(), realm = "bunk")),
              {
                server().handler.requireAuth = true
                buildTaskForFailure(projectDirectory, HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart)).run {
                  taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME_FORMAT.task(chart))
                  taskFailed(HelmPlugin.PUBLISH_TASK_NAME_FORMAT.task(chart))
                  server().handler.let {
                    assertEquals(1, it.requests.size, "Executed one request")
                    assertFalse(it.requests[0].hasHeader("Authorization"),
                        "Request has no authorization: ${it.requests[0]}")
                  }
                }
              }
          )
        }
      }
    }
  }
})
