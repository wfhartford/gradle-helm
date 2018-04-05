package ca.cutterslade.gradle.helm

import com.google.common.io.MoreFiles
import com.google.common.io.RecursiveDeleteOption
import org.glassfish.grizzly.http.Method
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

object HelmPluginSimpleChartSpec : Spek({
  val projectName = "create-helm-chart-functional-test-build"
  val projectVersion = "0.1.0"
  val projectDirectory: Path = Files.createTempDirectory(HelmPluginSimpleChartSpec::class.simpleName)
  val buildFile = projectDirectory.resolve("build.gradle")
  val settingsFile = projectDirectory.resolve("settings.gradle")
  var _server: GrizzlyServer? = null
  fun server() = _server ?: throw IllegalStateException("_server is null")

  beforeGroup {
    _server = GrizzlyServer()
    server().start()
  }

  beforeEachTest {
    buildFile.toFile().writeText("""
      plugins { id 'ca.cutterslade.helm' }
      version = '$projectVersion'
      helm { repository { url 'http://localhost:${server().port}' } }
      """.trimIndent())
    settingsFile.toFile().writeText("rootProject.name = '$projectName'\n")
  }

  afterEachTest {
    server().handler.reset()
  }

  afterGroup {
    server().close()
    _server = null
    MoreFiles.deleteRecursively(projectDirectory, RecursiveDeleteOption.ALLOW_INSECURE)
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

    it("can deploy a chart") {
      buildTask(projectDirectory, HelmPlugin.DEPLOY_TASK_NAME).run {
        taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME)
        taskSuccess(HelmPlugin.DEPLOY_TASK_NAME)
        server().handler.let {
          assertEquals(1, it.requests.size, "Deployed with a single request")
          assertEquals(Method.PUT, it.requests[0].method, "Deploy request method")
          assertEquals("/$projectName-$projectVersion.tgz", it.requests[0].path, "Deploy request path")
          projectDirectory.isFile("build", "helm", "package", "$projectName-0.1.0.tgz").run {
            assertEquals(toFile().length(), it.requests[0].contentLength, "Deployed package size")
          }
        }
      }
    }
    it("cannot deploy a chart if server requires authentication and none provided") {
      server().handler.requireAuth = true
      buildTaskForFailure(projectDirectory, HelmPlugin.DEPLOY_TASK_NAME).run {
        taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME)
        taskFailed(HelmPlugin.DEPLOY_TASK_NAME)
        assertTrue(output.contains("code=401, message=Unauthorized"),
            "Build output should contain 'Response : Unauthorized'")
      }
    }

    it("can deploy a chart if server requires authentication and correct credentials provided no realm") {
      withModifiedFile(
          projectDirectory.resolve("build.gradle"),
          {
            if (it.startsWith("helm")) """
            |helm {
            |  repository {
            |    url 'http://localhost:${server().port}'
            |    username 'user'
            |    password 'pass'
            |  }
            |}
            """.trimMargin()
            else it
          },
          {
            server().handler.requireAuth = true
            buildTask(projectDirectory, HelmPlugin.DEPLOY_TASK_NAME).run {
              taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME)
              taskSuccess(HelmPlugin.DEPLOY_TASK_NAME)
              server().handler.let {
                assertEquals(2, it.requests.size, "Required two requests")
                assertFalse(it.requests[0].hasHeader("Authorization"),
                    "First request has no authorization: ${it.requests[0]}")
                assertTrue(it.requests[1].hasHeader("Authorization"),
                    "Second request has authorization: ${it.requests[1]}")
                it.requests.forEachIndexed { index, request ->
                  assertEquals(Method.PUT, request.method, "Deploy request[$index] method: $request")
                  assertEquals("/$projectName-$projectVersion.tgz",
                      request.path,
                      "Deploy request[$index] path: $request")
                  projectDirectory.isFile("build", "helm", "package", "$projectName-0.1.0.tgz").run {
                    assertEquals(toFile().length(),
                        request.contentLength,
                        "Deployed package size in request[$index]: $request")
                  }
                }
              }
            }
          })
    }
    it("can deploy a chart if server requires authentication and correct credentials provided with realm") {
      withModifiedFile(
          projectDirectory.resolve("build.gradle"),
          {
            if (it.startsWith("helm")) """
            |helm {
            |  repository {
            |    url 'http://localhost:${server().port}'
            |    username 'user'
            |    password 'pass'
            |    authRealm 'test'
            |  }
            |}
            """.trimMargin()
            else it
          },
          {
            server().handler.requireAuth = true
            buildTask(projectDirectory, HelmPlugin.DEPLOY_TASK_NAME).run {
              taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME)
              taskSuccess(HelmPlugin.DEPLOY_TASK_NAME)
              server().handler.let {
                assertEquals(2, it.requests.size, "Required two requests")
                assertFalse(it.requests[0].hasHeader("Authorization"),
                    "First request has no authorization: ${it.requests[0]}")
                assertTrue(it.requests[1].hasHeader("Authorization"),
                    "Second request has authorization: ${it.requests[1]}")
                it.requests.forEachIndexed { index, request ->
                  assertEquals(Method.PUT, request.method, "Deploy request[$index] method: $request")
                  assertEquals("/$projectName-$projectVersion.tgz",
                      request.path,
                      "Deploy request[$index] path: $request")
                  projectDirectory.isFile("build", "helm", "package", "$projectName-0.1.0.tgz").run {
                    assertEquals(toFile().length(),
                        request.contentLength,
                        "Deployed package size in request[$index]: $request")
                  }
                }
              }
            }
          })
      it("cannot deploy a chart if server requires authentication and correct credentials provided with wrong realm") {
        withModifiedFile(
            projectDirectory.resolve("build.gradle"),
            {
              if (it.startsWith("helm")) """
            |helm {
            |  repository {
            |    url 'http://localhost:${server().port}'
            |    username 'user'
            |    password 'pass'
            |    authRealm 'bunk'
            |  }
            |}
            """.trimMargin()
              else it
            },
            {
              server().handler.requireAuth = true
              buildTaskForFailure(projectDirectory, HelmPlugin.DEPLOY_TASK_NAME).run {
                taskUpToDate(HelmPlugin.PACKAGE_TASK_NAME)
                taskFailed(HelmPlugin.DEPLOY_TASK_NAME)
                server().handler.let {
                  assertEquals(1, it.requests.size, "Executed one request")
                  assertFalse(it.requests[0].hasHeader("Authorization"),
                      "Request has no authorization: ${it.requests[0]}")
                }
              }
            })
      }
    }
  }
})
