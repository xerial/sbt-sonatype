# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is sbt-sonatype, an sbt plugin for publishing Scala/Java projects to Maven Central through Sonatype Nexus REST API. The plugin supports both legacy Sonatype OSSRH and the new Sonatype Central portal.

## Development Commands

### Development Workflow
- Before development, always create a new branch 

### Build and Testing
- `sbt compile` - Compile the plugin
- `sbt test` - Run tests using airspec framework
- `sbt scripted` - Run scripted tests (integration tests for sbt plugins)
- `sbt format` - Format code using scalafmt (alias for `scalafmtAll; scalafmtSbt`)

### Publishing (for plugin development)
- `sbt publishSigned` - Publish signed artifacts to local staging
- `sbt sonatypeBundleRelease` - Release to Sonatype/Maven Central

### Code Quality
- The project uses scalafmt for formatting with the `format` command alias
- Tests use airspec framework (not ScalaTest)
- Cross-builds for Scala 2.12 and 3.6.2
- Supports sbt 1.x and 2.0.0-M4

## Architecture Overview

### Core Components

**Main Plugin Class (`Sonatype.scala`)**
- `Sonatype` object extends `AutoPlugin` and provides all sbt commands
- Defines settings keys, tasks, and command implementations
- Handles both legacy Sonatype OSSRH and new Sonatype Central workflows

**Service Layer**
- `SonatypeService` - Handles legacy OSSRH operations (staging repositories)
- `SonatypeCentralService` - Handles new Sonatype Central operations (bundle uploads)
- Both services abstract the underlying REST API interactions

**Client Layer**
- `SonatypeClient` - Legacy OSSRH REST API client
- `SonatypeCentralClient` - New Sonatype Central API client using lumidion library

### Key Architectural Patterns

**Dual Publishing Strategy**
The plugin supports two distinct publishing workflows:
1. **Legacy OSSRH**: Multi-step process (open → upload → close → promote)
2. **Sonatype Central**: Single bundle upload with automatic or manual release

**Bundle-based Publishing**
- Creates local staging bundles in `target/sonatype-staging/(version)`
- For OSSRH: Uploads bundle to staging repository, then promotes
- For Central: Zips bundle and uploads directly

**Command Routing**
Commands like `sonatypeBundleRelease` automatically route to the appropriate service based on `sonatypeCredentialHost` setting.

### Important Settings
- `sonatypeCredentialHost` - Determines which service to use
  - `oss.sonatype.org` (default) - Legacy OSSRH
  - `s01.oss.sonatype.org` - Newer OSSRH
  - `central.sonatype.com` - New Sonatype Central
- `sonatypePublishToBundle` - Points to local bundle directory for staging
- `sonatypeProfileName` - Organization name for repository operations

### Cross-Compatibility Support
- `SonatypeCompat.scala` provides version-specific compatibility across Scala 2/3
- Separate source directories for Scala 2 (`scala-2/`) and Scala 3 (`scala-3/`)
- Plugin supports both sbt 1.x and 2.0.0-M4

### Testing Structure
- Unit tests in `src/test/scala/` using airspec
- Integration tests in `src/sbt-test/` using sbt's scripted testing framework
- Test projects simulate real-world plugin usage scenarios