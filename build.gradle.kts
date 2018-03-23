import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "ca.cutterslade.gradle"
version = "1.0.0-SNAPSHOT"

repositories {
  jcenter()
}

plugins {
  kotlin("jvm") version "1.2.30"
}

dependencies {
  compile(kotlin("stdlib"))
  compile(kotlin("stdlib-jre8"))
  compile(kotlin("reflect"))
  compile(gradleApi())
}

tasks.withType(KotlinCompile::class.java).all {
  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_1_8.toString()
  }
}
