# Ballerina Otel Observability Extension

[![Build](https://github.com/ballerina-platform/module-ballerina-otel/workflows/Daily%20Build/badge.svg)](https://github.com/ballerina-platform/module-ballerina-otel/actions?query=workflow%3A"Daily+Build")
[![GitHub Last Commit](https://img.shields.io/github/last-commit/ballerina-platform/module-ballerina-otel.svg)](https://github.com/ballerina-platform/module-ballerina-otel/commits/main)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![codecov](https://codecov.io/gh/ballerina-platform/module-ballerina-otel/branch/main/graph/badge.svg?token=5GCQ36HBEB)](https://codecov.io/gh/ballerina-platform/module-ballerina-otel)

## Runtime compatibility

`main` uses OpenTelemetry 1.65.0 and requires the Ballerina runtime
`2201.14.0-SNAPSHOT` configured in `gradle.properties`.
The Gradle build downloads and uses that distribution. Applications using this
extension must also use a compatible runtime: the standard 2201.13.4 distribution
contains the older OpenTelemetry API/context and is supported by `version-0.9.0`.
Both branches use `grpc` or `http/protobuf` for traces and metrics; `http/json`
is recognized but not yet supported.

### Package selection across Ballerina updates

- Keep `ballerina/otel:0.9.x` built with Ballerina `2201.13.x` for Update 13 users.
- Build `ballerina/otel:1.0.0` with Update 14 and bundle it in `2201.14.0`.
- Central filters candidates using `package.json`'s `ballerina_version`, written by
  the compiler that packs the BALA. The `distribution` declaration alone is not
  the registry compatibility gate. Use the Update 14 release compiler for the GA artifact.
- Update 14 also considers older packages distribution-compatible. Applications
  pinned to `0.9.x` need an explicit upgrade to `1.0.0`; version ranges and lockfiles
  do not automatically cross this major-version boundary.

The distribution consumes the `org.ballerinalang:otel-extension-ballerina:1.0.0`
Maven ZIP. For local distribution builds, publish it to Maven local:

```sh
./gradlew :otel-extension-ballerina:publishToMavenLocal :otel-extension-native:publishToMavenLocal -x commitTomlFiles
```

Then build the distribution with `otelVersion=1.0.0`. Release CI also needs these
Maven artifacts in its configured repository and the BALA published to Central
for users who resolve it there.

See the registry's [distribution compatibility filter](https://github.com/wso2-enterprise/ballerina-registry/blob/main/projects/package_api/modules/utils/utils.bal#L261)
and [dependency version ranges](https://github.com/wso2-enterprise/ballerina-registry/blob/main/projects/package_api/modules/utils/utils.bal#L473).

## Building from the Source

### Setting Up the Prerequisites

1. Download and install Java SE Development Kit (JDK) version 21 (from one of the following locations).

    * [Oracle](https://www.oracle.com/java/technologies/downloads/)

    * [OpenJDK](https://adoptopenjdk.net/)

      > **Note:** Set the JAVA_HOME environment variable to the path name of the directory into which you installed JDK.

### Building the Source

Execute the commands below to build from source.

1. To build the library:

        ./gradlew clean build

2. To run the integration tests:

        ./gradlew clean test

## Contributing to Ballerina

As an open source project, Ballerina welcomes contributions from the community.

For more information, go to the [contribution guidelines](https://github.com/ballerina-platform/ballerina-lang/blob/master/CONTRIBUTING.md).

## Code of Conduct

All contributors are encouraged to read the [Ballerina Code of Conduct](https://ballerina.io/code-of-conduct).

## Useful Links

* Discuss about code changes of the Ballerina project in [ballerina-dev@googlegroups.com](mailto:ballerina-dev@googlegroups.com).
* Chat live with us via our [Discord server](https://discord.gg/ballerinalang).
* Post all technical questions on Stack Overflow with the [#ballerina](https://stackoverflow.com/questions/tagged/ballerina) tag.
* View the [Ballerina performance test results](https://github.com/ballerina-platform/ballerina-lang/blob/master/performance/benchmarks/summary.md).
