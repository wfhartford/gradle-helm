# gradle-helm

[![CircleCI](https://circleci.com/gh/wfhartford/gradle-helm.svg?style=svg)](https://circleci.com/gh/wfhartford/gradle-helm)

Gradle plugin which helps to create, manage, and publish helm charts by integrating helm commands into your gradle build.

This plugin can be installed from the [gradle plugin portal](https://plugins.gradle.org/plugin/ca.cutterslade.helm).

## Status
This plugin is under active development and subject to change. If you have any suggestions or requests, please feel free to create an issue or pull request.

## Plugin Configuration
The plugin adds two extension objects to the project which can be used to configure the plugin and the charts which it will manage:
```gradle
helm {
  install {
    // allows configuration of various details of the helm installation managed
    // by the plugin. The only option that is likely to require customisation
    // is the helm version to use. Each version of the plugin will default to
    // the newest version of helm available at the time.
    version = 'v2.8.2'
  }
  repository {
    // allows configuration of the repository to which the publish tasks will
    // upload the packaged chart.
    url 'http://repository.myorg.org/production-helm-charts'
    username 'myusername'
    password 'isasecret'
    authRealm 'repository authentication realm'
    requestHeaders = [
      ['HeaderName:', 'header value'],
      ['OtherHeader:', 'other value']
    ]
    clientConfigurator = { OkHttpClient.Builder builder ->
      // Allows arbitrary configuration of the HTTP client
    }
  }
}

charts {
  // The charts extension allows you to define an arbitrary number of charts
  // which the plugin will manage. Each chart is defined in a block using the
  // name of the chart; the generated task names depend on the chart name.
  myFirstHelmChart {
    // Within the chart block, a few options can be defined:
    
    // If not set, the chart version defaults to the project version. Note
    // that helm requires charts use symantic versioning, so if your project
    // does not, you will have to define a version like this.
    chartVersion '1.2.3'
    
    // If not set, the app version defaults to the project version. Helm does
    // not enforce any requirements on the app version
    appVersion '1.2.3.down-with^semver'
    
    // If not set, the chart directory defaults to
    // 'src/helm/resources/${chartName}'. If the chart directory is outside of
    // 'src/helm/resources' extra configuration will be required to have the
    // chart files processed into the helm source set output directory.
    chartDir 'src/helm/resources/my-first-chart'
    
    // Each chart can include configuration which changes how the chart is
    // validated by the lint command.
    lint.values = ['valueKey':'some value']
    lint.valuesFiles = ['lint-values.yaml']
  }
}
```

## Plugin Tasks
### Static Tasks
Whenever the plugin is installed, the following tasks will be created.

| Task Name | Description | Type |
| --------- | ----------- | ---- |
| helmVerifyArchitecture | Ensures that the build is running on a supported CPU architecture. | ad-hoc |
| helmVerifyOperatingSystem | Ensures that the build is running on a supported Operating System. | ad-hoc |
| helmVerifySupport | Depends to other verify tasks. | ad-hoc |
| downloadHelm | Download the appropriate helm distribution for the current architecture and operating system. | `ca.cutterslade.gradle.helm.DownloadTask` |
| installHelm | Install helm into the local installation directory. | `ca.cutterslade.gradle.helm.InstallTask` |
| initializeHelm | Initialize the helm home directory. | `ca.cutterslade.gradle.helm.InitializeTask` |
| getHelmVersion | Execute `helm version --client`, and save the output. | `ca.cutterslade.gradle.helm.GetHelmVersionTask` |
| checkHelmVersion | Check that the output captured by the `getHelmVersion` task matches the requested version of Helm | `ca.cutterslade.gradle.helm.CheckHelmVersionTask` |

### Dynamic Tasks
For each chart configured in the build file, the plugin will create a set of tasks which operate on that chart. In the names used for the following tasks, the chart name will be munged to camel case.

| Task Name Format | Description | Type |
| ---------------- | ----------- | ---- |
| ensureNo${chartName}Chart | Ensure that the defined chart does not exist, failing the build if the chart directory exists. | `ca.cutterslade.gradle.helm.EnsureNoChartTask` |
| create${chartName}Chart | Create the configured chart using the 'helm create' command. | `ca.cutterslade.gradle.helm.CreateChartTask` |
| lint${chartName}Chart | Validate the chart by executing the 'helm lint' command. | `ca.cutterslade.gradle.helm.LintTask` |
| package${chartName}Chart | Package the chart by executing the 'helm package' command. | `ca.cutterslade.gradle.helm.PackageTask` |
| publish${chartName}Chart | Publish the chart to the configured repository. | `ca.cutterslade.gradle.helm.PublishTask` |
