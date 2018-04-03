import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.platform.gradle.plugin.JUnitPlatformPlugin

group = "ca.cutterslade.gradle"
version = "1.0.0-SNAPSHOT"

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
  compile("com.github.kittinunf.fuel:fuel:1.12.1")

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

