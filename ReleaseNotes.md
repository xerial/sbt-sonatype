Release Notes
===
# 2.4
- Fixes a bug in propagating publishTo setting to sub projects [#76](https://github.com/xerial/sbt-sonatype/issues/76)
- Drop sbt 0.13.x support

# 2.3
- Allow setting the credentials with the environment variables `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`

# 2.2
- Fixes typo [#61](https://github.com/xerial/sbt-sonatype/pull/61) in GitHubHosting and GitLabHosting capitilization
- If you are not using these keys, no need to upgrade to this version.

# 2.1
- Fixes [#55](https://github.com/xerial/sbt-sonatype/issues/55) with `sonatypePublishTo` setting:  
```scala
publishTo := sonatypePublishTo
``` 
- Add shortcut `sonatypeProjectHosting` for quickly setting `homepage`, `scmInfo` and `developers`


# 2.0 
- Support sbt-0.13, 1.0.0-M5, 1.0.0-M6, and 1.0.0-RC3

# 2.0.0-M1
- Add sbt-1.0.0-M5 (for Scala 2.12), sbt-0.13.x support
- **IMPORTANT** sbt-sonatype no longer modifies `publishTo` settings. You need to manually set the Sonatype repository name as below:
```scala
publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)
```

# 1.1
- Add sonatypeOpen command for supporting more complex workflows (see the usage example in [workflow.md](workflow.md))

# 1.0
- The first major release (stable)

# 0.5.1
- `sonatypeReleaseAll (sonatypeProfileName)` command can be used standalone without preparing sbt project files.

# 0.5.0
 - sonatypeRelease etc. are now sbt Commands. 
 - No need exists to include `sonatypeSettings`. This will be automcatically loaded

# 0.4.0
 - Simplified the configuration for multi-module build
 - Migration guide for 0.3.x, 0.2.x users: Just include `Sonatype.sonatypeSettings` in `(project root)/sonatype.sbt` file.

# 0.3.3
 - Retry request on 500 error from sonatype REST API
 - Improved log message

# 0.3.0
 - sbt-sonatype is now an auto plugin
 - Migration guide from 0.2.x 
   - `profileName` -> `sonatypeProfileName`
   - No need to include sonatypeSettings
