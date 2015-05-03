- Release 0.4.0
 - Simplified the configuration for multi-module build
 - Migration guide for 0.3.x, 0.2.x users: Just include Sonatype.sonatypeSetting in (project root)/sonatype.sbt file.

- Release 0.3.3
 - Retry request on 500 error from sonatype REST API
 - Improved log message

- Release 0.3.0
 - sbt-sonatype is now an auto plugin
 - Migration guide from 0.2.x 
   - `profileName` -> `sonatypeProfileName`
   - No need to include sonatypeSettings
