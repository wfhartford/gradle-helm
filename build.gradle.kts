import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.platform.gradle.plugin.JUnitPlatformPlugin
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

group = "ca.cutterslade.gradle"
version = "1.0.0-beta-7"

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
  kotlin("jvm") version "1.2.31"
  id("com.gradle.plugin-publish") version "0.9.10"
}

java.sourceSets.create("functionalTest") {
  compileClasspath += project.java.sourceSets["main"].output
  runtimeClasspath += output
  runtimeClasspath += compileClasspath
  runtimeClasspath += project.configurations["runtime"]
  runtimeClasspath += project.configurations["functionalTestRuntime"]
}

project.configurations["functionalTestCompile"].extendsFrom(project.configurations["compile"])
project.configurations["functionalTestRuntime"].extendsFrom(project.configurations["runtime"])

dependencies {
  compile(kotlin("stdlib"))
  compile(kotlin("stdlib-jre8"))
  compile(kotlin("reflect"))
  compile(gradleApi())
  compile("com.squareup.okhttp3:okhttp:3.10.0")

  testCompile(kotlin("test"))
  testCompile("org.jetbrains.spek:spek-junit-platform-engine:1.1.5")

  add("functionalTestCompile", kotlin("test"))
  add("functionalTestCompile", "org.jetbrains.spek:spek-api:1.1.5")
  add("functionalTestCompile", "com.google.guava:guava:24.1-jre")
  add("functionalTestCompile", "org.glassfish.grizzly:grizzly-http-server:2.4.0")
  add("functionalTestRuntime", "org.junit.platform:junit-platform-engine:1.1.0")
  add("functionalTestRuntime", "org.jetbrains.spek:spek-junit-platform-engine:1.1.5")
}

gradlePlugin {
  (plugins) {
    "gradle-helm" {
      id = "ca.cutterslade.helm"
      implementationClass = "ca.cutterslade.gradle.helm.HelmPlugin"
    }
  }
  testSourceSets(java.sourceSets["functionalTest"])
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
  "functionalTest"(Test::class) {
    useJUnitPlatform()
    testClassesDirs = java.sourceSets["functionalTest"].output.classesDirs
    classpath = java.sourceSets["functionalTest"].runtimeClasspath
    mustRunAfter("test")
  }.also { tasks["check"].dependsOn(it) }

  withType(KotlinCompile::class.java).all {
    kotlinOptions {
      jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
  }
}

