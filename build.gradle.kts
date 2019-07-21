import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.platform.gradle.plugin.JUnitPlatformPlugin
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

group = "ca.cutterslade.gradle"
version = "1.0.0-beta-10"

repositories {
  jcenter()
}

buildscript {
  dependencies {
    classpath("org.junit.platform:junit-platform-gradle-plugin:1.1.0")
  }
}

plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  `junit-test-suite`
  kotlin("jvm") version "1.3.11"
  id("com.gradle.plugin-publish") version "0.10.0"
}

sourceSets.create("functionalTest") {
  compileClasspath += sourceSets["main"].output
  runtimeClasspath += output
  runtimeClasspath += compileClasspath
  runtimeClasspath += configurations["runtime"]
  runtimeClasspath += configurations["functionalTestRuntime"]
}

configurations["functionalTestCompile"].extendsFrom(configurations["compile"])
configurations["functionalTestRuntime"].extendsFrom(configurations["runtime"])

dependencies {
  compile(kotlin("stdlib"))
  compile(kotlin("stdlib-jdk8"))
  compile(kotlin("reflect"))
  compile(gradleApi())
  compile("com.squareup.okhttp3:okhttp:3.11.0")

  testCompile(kotlin("test"))
  testCompile("org.jetbrains.spek:spek-junit-platform-engine:1.1.5")

  add("functionalTestCompile", kotlin("test"))
  add("functionalTestCompile", "org.jetbrains.spek:spek-api:1.1.5")
  add("functionalTestCompile", "com.google.guava:guava:27.0-jre")
  add("functionalTestCompile", "org.glassfish.grizzly:grizzly-http-server:2.4.3")
  add("functionalTestRuntime", "org.junit.platform:junit-platform-engine:1.3.1")
  add("functionalTestRuntime", "org.jetbrains.spek:spek-junit-platform-engine:1.1.5")
}

gradlePlugin {
  plugins {
    create("helm") {
      id = "ca.cutterslade.helm"
      implementationClass = "ca.cutterslade.gradle.helm.HelmPlugin"
    }
  }
  testSourceSets(sourceSets["functionalTest"])
}

pluginBundle {
  website = "https://github.com/wfhartford/gradle-helm"
  vcsUrl = "https://github.com/wfhartford/gradle-helm.git"
  tags = listOf("helm")
  description = "Plugin supporting basic helm commands for a gradle build."

  (plugins) {
    "helm" {
      id = "ca.cutterslade.helm"
      displayName = "Gradle Helm Plugin"
      version = project.version.toString().replace("-SNAPSHOT", "-${DateTimeFormatter.ofPattern("uuuuMMddHHmmss").format(LocalDateTime.now())}")
    }
  }
}

tasks["publishPlugins"].dependsOn(tasks["check"])

tasks {
  create<Test>("functionalTest") {
    useJUnitPlatform()
    testClassesDirs = sourceSets["functionalTest"].output.classesDirs
    classpath = sourceSets["functionalTest"].runtimeClasspath
    mustRunAfter("test")
  }.also { get("check").dependsOn(it) }

  withType(KotlinCompile::class.java).all {
    kotlinOptions {
      jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
  }
}

