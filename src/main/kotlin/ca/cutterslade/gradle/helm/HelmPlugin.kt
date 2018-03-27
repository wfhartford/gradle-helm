package ca.cutterslade.gradle.helm

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.*
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
    val ENSURE_NO_CHART_TASK_NAME = "ensureNoHelmChart"
    val CREATE_TASK_NAME = "createHelmChart"
    val LINT_TASK_NAME = "lintHelmChart"
    val PACKAGE_TASK_NAME = "packageHelmChart"

    val TASKS_NAMES = setOf(
        VERIFY_ARCH_TASK_NAME,
        VERIFY_OS_TASK_NAME,
        VERIFY_TASK_NAME,
        DOWNLOAD_TASK_NAME,
        INSTALL_TASK_NAME,
        INITIALIZE_TASK_NAME,
        ENSURE_NO_CHART_TASK_NAME,
        CREATE_TASK_NAME,
        LINT_TASK_NAME,
        PACKAGE_TASK_NAME
    )

    val SUPPORTED_ARCHITECTURES = setOf("amd64", "x86_64")
  }

  override fun apply(project: Project) {
    project.run {
      plugins.apply(JavaBasePlugin::class.java)
      val sourceSet = withJava { sourceSets.create("helm") }
      extensions.create("helm", HelmExtension::class.java, project, objects)

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
        val initTask = INITIALIZE_TASK_NAME(InitializeTask::class) {
          dependsOn(verifyTask, installTask)
        }
        val ensureNoChartTask = ENSURE_NO_CHART_TASK_NAME(EnsureNoChartTask::class)
        CREATE_TASK_NAME(CreateChartTask::class) {
          dependsOn(ensureNoChartTask, verifyTask, initTask)
        }
        LINT_TASK_NAME(LintTask::class) {
          dependsOn(verifyTask, initTask, sourceSet.processResourcesTaskName)
          tasks["check"].dependsOn(this)
        }
        val packageTask = PACKAGE_TASK_NAME(PackageTask::class) {
          dependsOn(verifyTask, initTask, tasks["check"], sourceSet.processResourcesTaskName)
          tasks["assemble"].dependsOn(this)
        }
      }
    }
  }
}

open class HelmExtension @Inject constructor(private var project: Project, private var objectFactory: ObjectFactory) {
  val install by lazy { objectFactory.newInstance(HelmInstallation::class.java, project) }
  val lint by lazy { objectFactory.newInstance(HelmLint::class.java, project) }
  var chartName by DefaultingDelegate { project.name }
  var chartVersion by DefaultingDelegate { project.version }
  var appVersion by DefaultingDelegate { project.version }
  var chartRepository by DefaultingDelegate { "https://kubernetes-charts.storage.googleapis.com/" }
  var chartDir by DefaultingDelegate {
    project.helmSource().resources.srcDirs.first().toPath().resolve(chartName).toFile()
  }
}

open class HelmInstallation @Inject constructor(private val project: Project) {
  var version: String by DefaultingDelegate { "v2.8.2" }
  var os: OperatingSystem by DefaultingDelegate { OperatingSystem.detect() }
  var helmFilename: String by DefaultingDelegate { os.filename(this) }
  var url: String by DefaultingDelegate { os.url(this) }
  var workingDir: File by DefaultingDelegate { project.file("${project.buildDir}/helm") }
  var archiveFile: File by DefaultingDelegate { project.file("$workingDir/$helmFilename") }
  var homeDir: File by DefaultingDelegate { project.file("$workingDir/home") }
  var packageDir: File by DefaultingDelegate { project.file("$homeDir/package") }
  var installDir: File by DefaultingDelegate { project.file("$workingDir/install") }
  var executable: File by DefaultingDelegate { project.file("$installDir/${os.executable()}") }
}

open class HelmLint @Inject constructor(private val project: Project) {
  var strict: Boolean by DefaultingDelegate { false }
  var values: Map<String, String> by DefaultingDelegate { mapOf<String, String>() }
  var valuesFiles: List<Any> by DefaultingDelegate { listOf<Any>() }
}

class DefaultingDelegate<T>(private val supplier: () -> T) {
  var value: T? = null
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
  MAC("max os x", filenamePart = "darwin");

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
internal operator fun <T> Callable<T>.invoke() = call()

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

open class InstallTask : Copy() {
  @InputFile
  val archive = Callable { helm().install.archiveFile }
  @OutputDirectory
  val install = Callable { helm().install.installDir }

  init {
    from(project.tarTree(project.resources.gzip(archive)))
    into(install)
  }
}

fun arg(arg: Any): CommandLineArgumentProvider = CommandLineArgumentProvider {
  when (arg) {
    is CommandLineArgumentProvider -> arg.asArguments()
    is Callable<*> -> arg(arg.call()).asArguments()
    is Iterable<*> -> arg.flatMap { it?.let { arg(it).asArguments() } ?: listOf() }
    else -> listOf(arg.toString())
  }
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

open class EnsureNoChartTask : DefaultTask() {
  @Internal
  val chartDir = Callable { helm().chartDir }
  @TaskAction
  fun noChart() {
    chartDir().run {
      toPath().resolve("Chart.yaml").also {
        if (Files.exists(it)) throw IllegalStateException("Cannot create chart when exists: '$it'")
      }
    }
  }
}

open class CreateChartTask : HelmExecTask() {
  @InputDirectory
  override val home = Callable { helm().install.homeDir }
  @OutputDirectory
  val chartDir = Callable { helm().chartDir }

  override fun helmArgs() = listOf(CommandLineArgumentProvider { listOf("create", chartDir().toString()) })
}

open class LintTask : HelmExecTask() {
  @InputDirectory
  val chart = Callable { helmSource().output.resourcesDir.toPath().resolve(helm().chartName).toFile() }
  @InputDirectory
  override val home = Callable { helm().install.homeDir }
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
          listOf("--values",
              project.file(file).toString())
        }
      },
      CommandLineArgumentProvider { listOf(chart().toString()) }
  )
}

open class PackageTask : HelmExecTask() {
  @OutputDirectory
  val packageDir = Callable { helm().install.packageDir }
  @InputDirectory
  override val home = Callable { helm().install.homeDir }
  @Input
  val appVersion = Callable { helm().appVersion }
  @Input
  val chartVersion = Callable { helm().chartVersion }
  @InputDirectory
  val chart = Callable { helmSource().output.resourcesDir }

  override fun helmArgs() = listOf(CommandLineArgumentProvider {
    listOf(
        "package",
        "--app-version", appVersion().toString(),
        "--version", chartVersion().toString(),
        "--destination", packageDir().toString(),
        "--save=false",
        chart().toString()
    )
  })
}
