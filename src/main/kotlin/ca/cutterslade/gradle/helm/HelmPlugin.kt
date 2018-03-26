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
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Callable
import javax.inject.Inject
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

open class HelmPlugin : Plugin<Project> {
  companion object {
    val VERIFY_ARCH_TASK_NAME = "helmVerifyArchitecture"
    val VERIFY_OS_TASK_NAME = "helmVerifyOperatingSystem"
    val VERIFY_TASK_NAME = "helmVerifySupport"
    val DOWNLOAD_TASK_NAME = "downloadHelm"
    val INSTALL_TASK_NAME = "installHelm"
    val INITIALIZE_TASK_NAME = "initializeHelm"
    val PACKAGE_TASK_NAME = "packageHelmChart"
    val SUPPORTED_ARCHITECTURES = setOf("amd64", "x86_64")
  }

  override fun apply(project: Project) {
    project.run {
      plugins.apply(JavaBasePlugin::class.java)
      withJava { sourceSets.create("helm") }
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
          dependsOn(downloadTask)
        }
        val initTask = INITIALIZE_TASK_NAME(InitializeTask::class) {
          dependsOn(installTask)
        }
        val packageTask = PACKAGE_TASK_NAME(PackageTask::class) {
          dependsOn(initTask)
        }
      }
    }
  }

  private fun <T : Task> Project.task(type: KClass<T>, name: String) =
      task(mapOf("type" to DownloadTask::class.java), name)
}

open class HelmExtension @Inject constructor(private var project: Project, private var objectFactory: ObjectFactory) {
  val install by lazy { objectFactory.newInstance(HelmInstallation::class.java, project) }
  var chartName by DefaultingDelegate { project.name }
  var chartVersion by DefaultingDelegate { project.version }
  var appVersion by DefaultingDelegate { project.version }
  var chartRepository by DefaultingDelegate { "https://kubernetes-charts.storage.googleapis.com/" }
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

class DefaultingDelegate<T>(private val supplier: () -> T) {
  var value: T? = null
  operator fun getValue(thisRef: Any?, property: KProperty<*>) = value ?: supplier()
  operator fun setValue(thisRef: Any?, property: KProperty<*>, arg: T) {
    value = arg
  }
}

internal fun <T> Any.withJava(function: JavaPluginConvention.() -> T): T =
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

abstract class HelmExecTask : Exec() {
  class Arg(val arg: Any) {
    override fun toString(): String =
        if (arg is Callable<*>) Arg(arg.call()).toString() else arg.toString()
  }

  @InputFile
  val helmExecutable = Callable { helm().install.executable }

  fun helmArgs(vararg args: Any) {
    commandLine(Arg(helmExecutable), *(args.map { Arg(it) }.toTypedArray()))
  }
}

open class InitializeTask : HelmExecTask() {
  @OutputDirectory
  val home = Callable { helm().install.homeDir }

  init {
    helmArgs("init", "--home", home, "--client-only")
  }
}

open class PackageTask : HelmExecTask() {
  @OutputDirectory
  val packageDir = Callable { helm().install.packageDir }
  @InputDirectory
  val home = Callable { helm().install.homeDir }
  @InputDirectory
  val chart = Callable { helmSource().output.resourcesDir }

  init {
    doFirst { packageDir().mkdirs() }
    helmArgs(
        "--home", home,
        "package",
        "--app-version", Callable { helm().appVersion },
        "--version", Callable { helm().chartVersion },
        "--destination", packageDir,
        "--save=false",
        chart
    )
  }
}
