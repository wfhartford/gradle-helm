package ca.cutterslade.gradle.helm

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.withConvention
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Callable
import javax.inject.Inject
import kotlin.reflect.KProperty

open class HelmPlugin : Plugin<Project> {
  companion object {
    val VERIFY_ARCH_TASK_NAME = "helmVerifyArchitecture"
    val VERIFY_OS_TASK_NAME = "helmVerifyOperatingSystem"
    val VERIFY_TASK_NAME = "helmVerifySupport"
    val DOWNLOAD_TASK_NAME = "downloadHelm"
    val INSTALL_TASK_NAME = "installHelm"
    val INITIALIZE_TASK_NAME = "initializeHelm"

    val ENSURE_NO_CHART_TASK_NAME_FORMAT = "ensureNo%sChart"
    val CREATE_TASK_NAME_FORMAT = "create%sChart"
    val LINT_TASK_NAME_FORMAT = "lint%sChart"
    val PACKAGE_TASK_NAME_FORMAT = "package%sChart"
    val PUBLISH_TASK_NAME_FORMAT = "publish%sChart"

    val CONSTANT_TASKS_NAMES = setOf(
        VERIFY_ARCH_TASK_NAME,
        VERIFY_OS_TASK_NAME,
        VERIFY_TASK_NAME,
        DOWNLOAD_TASK_NAME,
        INSTALL_TASK_NAME,
        INITIALIZE_TASK_NAME
    )

    val VARIABLE_TASK_NAME_FORMATS = setOf(
        ENSURE_NO_CHART_TASK_NAME_FORMAT,
        CREATE_TASK_NAME_FORMAT,
        LINT_TASK_NAME_FORMAT,
        PACKAGE_TASK_NAME_FORMAT,
        PUBLISH_TASK_NAME_FORMAT
    )

    val VARIABLE_TASK_NAME_REGEXES = VARIABLE_TASK_NAME_FORMATS.map { it.replace("%s", "\\w+") }.map { Regex(it) }

    val SUPPORTED_ARCHITECTURES = setOf("amd64", "x86_64")

    fun chartTaskName(format: String, chartName: String) =
        format.format(chartName.split('-').joinToString("", transform = String::capitalize))
  }

  override fun apply(project: Project) {
    project.run {
      plugins.apply(JavaBasePlugin::class.java)
      val sourceSet = withJava { sourceSets.create("helm") }
      extensions.create("helm", HelmExtension::class.java, project, objects)

      val charts = container(HelmChart::class.java) { name -> HelmChart(name, project) }
      extensions.add("charts", charts)

      tasks {
        val archTask = VERIFY_ARCH_TASK_NAME {
          doLast {
            System.getProperty("os.arch").let { arch ->
              if (arch !in SUPPORTED_ARCHITECTURES) {
                throw UnsupportedOperationException("Helm is not supported on architectures '$arch', only $SUPPORTED_ARCHITECTURES")
              }
            }
          }
        }
        val osTask = VERIFY_OS_TASK_NAME {
          doLast {
            OperatingSystem.detect()
          }
        }
        val verifyTask = VERIFY_TASK_NAME {
          dependsOn(archTask, osTask)
        }
        val downloadTask = DOWNLOAD_TASK_NAME(DownloadTask::class) {
          dependsOn(verifyTask)
        }
        val installTask = INSTALL_TASK_NAME(InstallTask::class) {
          dependsOn(verifyTask, downloadTask)
        }
        INITIALIZE_TASK_NAME(InitializeTask::class) {
          dependsOn(verifyTask, installTask)
        }
      }

      this.afterEvaluate {
        tasks {
          charts.forEach { chart ->
            val verifyAndInitialize = arrayOf(tasks[VERIFY_TASK_NAME], tasks[INITIALIZE_TASK_NAME])
            val ensureNoChartTask = chart.formatName(ENSURE_NO_CHART_TASK_NAME_FORMAT)(EnsureNoChartTask::class) {
              taskChart = chart
            }
            chart.formatName(CREATE_TASK_NAME_FORMAT)(CreateChartTask::class) {
              taskChart = chart
              dependsOn(ensureNoChartTask, *verifyAndInitialize)
            }
            val lintTask = chart.formatName(LINT_TASK_NAME_FORMAT)(LintTask::class) {
              taskChart = chart
              dependsOn(sourceSet.processResourcesTaskName, *verifyAndInitialize)
              tasks["check"].dependsOn(this)
            }
            val packageTask = chart.formatName(PACKAGE_TASK_NAME_FORMAT)(PackageTask::class) {
              taskChart = chart
              dependsOn(
                  lintTask,
                  sourceSet.processResourcesTaskName,
                  *verifyAndInitialize
              )
              tasks["assemble"].dependsOn(this)
            }
            chart.formatName(PUBLISH_TASK_NAME_FORMAT)(PublishTask::class) {
              taskChart = chart
              dependsOn(packageTask)
              tasks.findByName("publish")?.dependsOn(this)
            }
          }
        }
      }
    }
  }
}

open class HelmExtension @Inject constructor(private val project: Project, private val objectFactory: ObjectFactory) {
  val install: HelmInstallation by lazy { objectFactory.newInstance(HelmInstallation::class.java, project) }
  @Suppress("unused")
  fun install(action: Action<HelmInstallation>) = action.execute(install)

  val lint: HelmLint by lazy { objectFactory.newInstance(HelmLint::class.java) }
  @Suppress("unused")
  fun lint(action: Action<HelmLint>) = action.execute(lint)

  val repository: HelmRepo by lazy { objectFactory.newInstance(HelmRepo::class.java) }
  @Suppress("unused")
  fun repository(action: Action<HelmRepo>) = action.execute(repository)
}

open class HelmChart(val name: String, private val project: Project) {
  var chartVersion by DefaultingDelegate { project.version.toString() }
  var appVersion by DefaultingDelegate { project.version.toString() }
  var chartDir: File by DefaultingDelegate {
    project.helmSource().resources.srcDirs.first().toPath().resolve(name).toFile()
  }

  internal fun formatName(taskFormat: String) = HelmPlugin.chartTaskName(taskFormat, name)
}

@Suppress("MemberVisibilityCanBePrivate")
open class HelmInstallation @Inject constructor(private val project: Project) {
  var version: String by DefaultingDelegate { "v2.8.2" }
  var os: OperatingSystem by DefaultingDelegate { OperatingSystem.detect() }
  var helmFilename: String by DefaultingDelegate { os.filename(this) }
  var url: String by DefaultingDelegate { os.url(this) }
  var workingDir: File by DefaultingDelegate { project.file("${project.buildDir}/helm") }
  var archiveFile: File by DefaultingDelegate { project.file("$workingDir/$helmFilename") }
  var homeDir: File by DefaultingDelegate { project.file("$workingDir/home") }
  var packageDir: File by DefaultingDelegate { project.file("$workingDir/package") }
  var installDir: File by DefaultingDelegate { project.file("$workingDir/install") }
  var executable: File by DefaultingDelegate { project.file("$installDir/${os.executable()}") }
}

open class HelmLint @Inject constructor() {
  var strict: Boolean by DefaultingDelegate { false }
  var values: Map<String, String> by DefaultingDelegate { mapOf<String, String>() }
  var valuesFiles: List<Any> by DefaultingDelegate { listOf<Any>() }
}

open class HelmRepo @Inject constructor() {
  var url by DefaultingDelegate { "https://kubernetes-charts.storage.googleapis.com/" }
  var username: String? = null
  var password: String? = null
  var authRealm: String? = null
  var requestHeaders by DefaultingDelegate { listOf<List<String>>() }
  var clientConfigurator by DefaultingDelegate { Action<OkHttpClient.Builder> {} }
}

class DefaultingDelegate<T>(private val supplier: () -> T) {
  private var value: T? = null
  operator fun getValue(thisRef: Any?, property: KProperty<*>) = value ?: supplier()
  operator fun setValue(thisRef: Any?, property: KProperty<*>, arg: T) {
    value = arg
  }
}

enum class OperatingSystem(
    private val osNamePrefix: String,
    private val executableSuffix: String = "",
    private val filenamePart: String = osNamePrefix
) {
  WINDOWS("windows", executableSuffix = ".exe"),
  LINUX("linux"),
  MAC("mac os x", filenamePart = "darwin");

  companion object {
    fun detect() = System.getProperty("os.name").toLowerCase().let { osName ->
      values().firstOrNull { osName.startsWith(it.osNamePrefix) }
          ?: throw UnsupportedOperationException("Unsupported operating system: $osName")
    }
  }

  fun url(install: HelmInstallation) =
      "https://storage.googleapis.com/kubernetes-helm/${filename(install)}"

  fun filename(install: HelmInstallation) = "helm-${install.version}-$filenamePart-amd64.tar.gz"

  fun executable() = "$filenamePart-amd64/helm$executableSuffix"
}

internal fun <T> Project.withJava(function: JavaPluginConvention.() -> T): T =
    withConvention(JavaPluginConvention::class, function)

internal fun Project.helm(): HelmExtension = extensions.getByType(HelmExtension::class.java)
internal fun Task.helm() = project.helm()
internal fun Project.helmSource(): SourceSet = withJava { sourceSets["helm"] }
internal fun Task.helmSource() = project.helmSource()
internal operator fun <T : Any?> Callable<T>.invoke() = call()

@Suppress("MemberVisibilityCanBePrivate")
open class DownloadTask : DefaultTask() {
  @OutputFile
  val location = Callable { helm().install.archiveFile }
  @Input
  val url = Callable { helm().install.url }

  @TaskAction
  fun download() {
    val loc = location()
    loc.parentFile.mkdirs()
    if (!loc.isFile) {
      URL(url()).openStream().use {
        Files.copy(it, loc.toPath())
      }
    }
  }
}

@Suppress("MemberVisibilityCanBePrivate")
open class InstallTask : Copy() {
  @InputFile
  val archive = Callable { helm().install.archiveFile }
  @OutputDirectory
  val install = Callable { helm().install.installDir }

  init {
    from(project.tarTree(project.resources.gzip(archive)))
    into(install)
  }

  final override fun from(vararg sourcePaths: Any): AbstractCopyTask = super.from(*sourcePaths)
  final override fun into(destdir: Any): AbstractCopyTask = super.into(destdir)
}

abstract class HelmExecTask : Exec() {
  @InputFile
  val helmExecutable = Callable { helm().install.executable }
  abstract val home: Callable<File>

  init {
    executable(object {
      override fun toString() = helmExecutable().toString()
    })
    argumentProviders.add(CommandLineArgumentProvider { listOf("--home", home().toString()) })
    argumentProviders.addAll(helmArgs())
  }

  abstract fun helmArgs(): List<CommandLineArgumentProvider>
}

open class InitializeTask : HelmExecTask() {
  @OutputDirectory
  override val home = Callable { helm().install.homeDir }

  override fun helmArgs() = listOf(CommandLineArgumentProvider {
    listOf(
        "init",
        "--client-only"
    )
  })
}

abstract class HelmChartExecTask : HelmExecTask() {
  @InputDirectory
  override val home = Callable { helm().install.homeDir }
  @Internal
  lateinit var taskChart: HelmChart
  @Input
  val chartName = Callable { taskChart.name }
  @Input
  val chartVersion = Callable { taskChart.chartVersion }
  @Input
  val appVersion = Callable { taskChart.appVersion }
  @Internal
  open val chartDir = Callable { taskChart.chartDir }
}

@Suppress("MemberVisibilityCanBePrivate")
open class EnsureNoChartTask : DefaultTask() {
  @Internal
  lateinit var taskChart: HelmChart
  @Input
  val chartDir = Callable { taskChart.chartDir }

  @TaskAction
  fun noChart() {
    chartDir().run {
      toPath().resolve("Chart.yaml").also {
        if (Files.exists(it)) throw IllegalStateException("Cannot create chart when exists: '$it'")
      }
    }
  }
}

open class CreateChartTask : HelmChartExecTask() {
  @OutputDirectory
  override val chartDir = super.chartDir

  override fun helmArgs() = listOf(CommandLineArgumentProvider { listOf("create", chartDir().toString()) })
}

@Suppress("MemberVisibilityCanBePrivate")
open class LintTask : HelmChartExecTask() {
  @InputDirectory
  val chart = Callable { helmSource().output.resourcesDir.toPath().resolve(chartName()).toFile() }
  @Input
  val strict = Callable { helm().lint.strict }
  @Input
  val values = Callable { helm().lint.values }
  @InputFiles
  val valuesFile = Callable { helm().lint.valuesFiles }

  override fun helmArgs() = listOf(
      CommandLineArgumentProvider { listOf("lint") },
      CommandLineArgumentProvider { if (strict()) listOf("--strict") else listOf() },
      CommandLineArgumentProvider { values().entries.flatMap { (key, value) -> listOf("--set", "$key=$value") } },
      CommandLineArgumentProvider {
        valuesFile().flatMap { file ->
          listOf("--values", project.file(file).toString())
        }
      },
      CommandLineArgumentProvider { listOf(chart().toString()) }
  )
}

@Suppress("MemberVisibilityCanBePrivate")
open class PackageTask : HelmChartExecTask() {
  @OutputFile
  val packageFile = Callable { project.file("${helm().install.packageDir}/${chartName()}-${chartVersion()}.tgz") }
  @InputDirectory
  val chart = Callable { helmSource().output.resourcesDir.toPath().resolve(chartName()).toFile() }

  override fun helmArgs() = listOf(CommandLineArgumentProvider {
    listOf(
        "package",
        "--app-version", appVersion(),
        "--version", chartVersion(),
        "--destination", packageFile().parent,
        "--save=false",
        chart().toString()
    )
  })
}

@Suppress("MemberVisibilityCanBePrivate")
open class PublishTask : DefaultTask() {
  @Internal
  lateinit var taskChart: HelmChart
  @Input
  val chartName = Callable { taskChart.name }
  @Input
  val chartVersion = Callable { taskChart.chartVersion }
  @InputDirectory
  val packageDir = Callable { helm().install.packageDir }
  @Input
  val repository = Callable { helm().repository }

  @TaskAction
  fun publishChart() {
    val repo = repository()
    val file = project.file("${packageDir()}/${chartName()}-${chartVersion()}.tgz")
    val url = "${repo.url}/${file.name}"
    val clientBuilder = OkHttpClient.Builder()
    val user = repo.username
    val pass = repo.password
    val realm = repo.authRealm
    if (user != null && pass != null) {
      clientBuilder.authenticator { _, response ->
        val challenges = response.challenges()
        when {
          null != response.request().header("Authorization") -> null
          challenges.isEmpty() -> null
          else -> response.request().newBuilder()
              .header(
                  "Authorization",
                  when {
                    challenges.any { it.scheme() == "Basic" && (null == realm || realm == it.realm()) } -> Credentials.basic(
                        user,
                        pass)
                    else -> throw Exception("Unsupported Challenges: $challenges")
                  }
              )
              .build().also { println("Authenticating with $it") }
        }
      }
    }
    repo.clientConfigurator.execute(clientBuilder)
    val client = clientBuilder.build()
    val request = Request.Builder()
        .url(url)
        .put(RequestBody.create(null, file))
        .also { repo.requestHeaders.forEach { (name, value) -> it.addHeader(name, value) } }
        .build()
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) throw Exception("Unable to publish helm chart: $response")
  }
}
