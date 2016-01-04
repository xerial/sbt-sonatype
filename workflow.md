## Single-session workflow example

```
sbt \
    -Dplugin.version=1.1-SNAPSHOT \
    -Dprofile.name="Example Staging Profile" \
    -Dhost=localhost \
    -Drepo=https://localhost:8081/nexus/service/local

[info] Loading global plugins from /Users/rouquett/.sbt/0.13/plugins
[info] Updating {file:/Users/rouquett/.sbt/0.13/plugins/}global-plugins...
[info] Resolving org.fusesource.jansi#jansi;1.4 ...
[info] Done updating.
...
[info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
>
```

### 1. Create a new staging repository for a given staging profile

Use either:
 - `sonatypeOpen "<description>"`
 - `sonatypeOpen "<profile name>" "<description>"`

As a side-effect, two settings will be updated in the SBT state:
 - `publishTo` will be set to the URL of the staging repository: `<repoURL>/staging/deployByRepositoryId/<repoID>`
 - `sonatypeStagingRepositoryProfile` will be set to an `StagingRepositoryProfile` object

```
> sonatypeOpen "Example of creating & publishing to a staging repository"
[info] Nexus repository URL: https://localhost:8081/nexus/service/local
[info] sonatypeProfileName = Example Staging Profile
[info] Reading staging profiles...
[info] Creating staging repository in profile: Example Staging Profile
[info] Created successfully: example_staging_profile-1001
[info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
> show sonatypeStagingRepositoryProfile
[info] [example_staging_profile-1001] status:open, profile:Example Staging Profile(12aadc320d178f)
> show publishTo
[info] Some(Example Staging Profile: https://localhost:8081/nexus/service/local/staging/deployByRepositoryId/example_staging_profile-1001)
>
```

### 2. Publish artifacts using the [Targeted Repository](https://github.com/sonatype/nexus-maven-plugins/blob/master/staging/maven-plugin/WORKFLOWS.md#targeted-repository) strategy.

Since the `publishTo` setting has been updated to point to the newly created staging repository,
the artifacts will be uploaded there. This is equivalent to uploading to a staging repository
using `curl` as described
[here](https://support.sonatype.com/hc/en-us/articles/213465868-Uploading-to-a-Staging-Repository-via-REST-API?page=1#comment_204178478).


```
> publish
[info] Updating {file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/}operations...
[info] Wrote .../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/target/scala-2.10/operations_2.10-0.1.pom
[info] Resolving org.fusesource.jansi#jansi;1.4 ...
[info] Done updating.
[info] :: delivering :: org.xerial.operations#operations_2.10;0.1 :: 0.1 :: release :: Mon Jan 04 09:38:56 PST 2016
[info] 	delivering ivy file to .../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/target/scala-2.10/ivy-0.1.xml
[info] 	published operations_2.10 to https://localhost:8081/nexus/service/local/staging/deployByRepositoryId/example_staging_profile-1001/org/xerial/operations/operations_2.10/0.1/operations_2.10-0.1.pom
[info] 	published operations_2.10 to https://localhost:8081/nexus/service/local/staging/deployByRepositoryId/example_staging_profile-1001/org/xerial/operations/operations_2.10/0.1/operations_2.10-0.1.jar
[info] 	published operations_2.10 to https://localhost:8081/nexus/service/local/staging/deployByRepositoryId/example_staging_profile-1001/org/xerial/operations/operations_2.10/0.1/operations_2.10-0.1-sources.jar
[info] 	published operations_2.10 to https://localhost:8081/nexus/service/local/staging/deployByRepositoryId/example_staging_profile-1001/org/xerial/operations/operations_2.10/0.1/operations_2.10-0.1-javadoc.jar
[success] Total time: 2 s, completed Jan 4, 2016 9:38:58 AM
>
```

### 3. Close, Drop or Release the staging repository

```
> show sonatypeStagingRepositoryProfile
[info] [example_staging_profile-1001] status:open, profile:Example Staging Profile(12aadc320d178f)
> sonatypeClose
[info] Nexus repository URL: https://localhost:8081/nexus/service/local
[info] sonatypeProfileName = Example Staging Profile
[info] Reading staging repository profiles...
[info] Reading staging profiles...
[info] Closing staging repository [example_staging_profile-1001] status:open, profile:Example Staging Profile(12aadc320d178f)
[info] Activity open started:2016-01-04T09:38:00.167-08:00, stopped:2016-01-04T09:38:00.196-08:00
[info] repositoryCreated: id:example_staging_profile-1001, user:rouquett, ip:137.79.22.153
[info] Activity close started:2016-01-04T09:48:59.092-08:00, stopped:2016-01-04T09:48:59.159-08:00
[info]      email: to:Nicolas.F.Rouquette@jpl.nasa.gov
[info] repositoryClosed: id:example_staging_profile-1001
[info] Closed successfully
[info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
> show sonatypeStagingRepositoryProfile
[info] [example_staging_profile-1001] status:closed, profile:Example Staging Profile(12aadc320d178f)
> sonatypeList
[info] Nexus repository URL: https://localhost:8081/nexus/service/local
[info] sonatypeProfileName = Example Staging Profile
[info] Reading staging profiles...
[info] Staging profiles (profileName:Example Staging Profile):
[info] StagingProfile(12aadc320d178f,Example Staging Profile,1)
> sonatypeStagingRepositoryProfiles
[info] Nexus repository URL: https://localhost:8081/nexus/service/local
[info] sonatypeProfileName = Example Staging Profile
[info] Reading staging repository profiles...
[info] Staging repository profiles (sonatypeProfileName:Example Staging Profile):
[info] [example_staging_profile-1001] status:closed, profile:Example Staging Profile(12aadc320d178f)
>

```

## Multi-session workflow example

It is possible to perform staging-related operations across separate SBT sessions.

### SBT session creating a staging repository

```
sbt \
    -Dplugin.version=1.1-SNAPSHOT \
    -Dprofile.name="Example Staging Profile" \
    -Dhost=localhost \
    -Drepo=https://localhost:8081/nexus/service/local

[info] Loading global plugins from /Users/rouquett/.sbt/0.13/plugins
[info] Updating {file:/Users/rouquett/.sbt/0.13/plugins/}global-plugins...
[info] Resolving org.fusesource.jansi#jansi;1.4 ...
[info] Done updating.
...
[info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
> sonatypeOpen "dummy"
 [info] Nexus repository URL: https://localhost:8081/nexus/service/local
 [info] sonatypeProfileName = Example Staging Profile
 [info] Reading staging profiles...
 [info] Creating staging repository in profile: Example Staging Profile
 [info] Created successfully: example_staging_profile-1005
 [info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
```

### Another SBT session closing a staging repository

```
sbt \
    -Dplugin.version=1.1-SNAPSHOT \
    -Dprofile.name="Example Staging Profile" \
    -Dhost=localhost \
    -Drepo=https://localhost:8081/nexus/service/local

[info] Loading global plugins from /Users/rouquett/.sbt/0.13/plugins
[info] Updating {file:/Users/rouquett/.sbt/0.13/plugins/}global-plugins...
[info] Resolving org.fusesource.jansi#jansi;1.4 ...
[info] Done updating.
...
[info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
> sonatypeStagingRepositoryProfiles
[info] Nexus repository URL: https://localhost:8081/nexus/service/local
[info] sonatypeProfileName = Example Staging Profile
[info] Reading staging repository profiles...
[info] Staging repository profiles (sonatypeProfileName:Example Staging Profile):
[info] [example_staging_profile-1005] status:open, profile:Example Staging Profile(12aadc320d178f)
> sonatypeClose example_staging_profile-1005
[info] Nexus repository URL: https://localhost:8081/nexus/service/local
[info] sonatypeProfileName = Example Staging Profile
[info] Reading staging repository profiles...
[info] Reading staging profiles...
[info] Closing staging repository [example_staging_profile-1005] status:open, profile:Example Staging Profile(12aadc320d178f)
[info] Activity open started:2016-01-04T10:55:21.775-08:00, stopped:2016-01-04T10:55:21.820-08:00
[info] repositoryCreated: id:example_staging_profile-1005, user:rouquett, ip:137.79.22.153
[info] Activity close started:2016-01-04T11:05:13.610-08:00, stopped:2016-01-04T11:05:13.659-08:00
[info]      email: to:Nicolas.F.Rouquette@jpl.nasa.gov
[info] repositoryClosed: id:example_staging_profile-1005
[info] Closed successfully
[info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
>
```

### Another SBT session promoting a staging repository

```
sbt \
    -Dplugin.version=1.1-SNAPSHOT \
    -Dprofile.name="Example Staging Profile" \
    -Dhost=localhost \
    -Drepo=https://localhost:8081/nexus/service/local

[info] Loading global plugins from /Users/rouquett/.sbt/0.13/plugins
[info] Updating {file:/Users/rouquett/.sbt/0.13/plugins/}global-plugins...
[info] Resolving org.fusesource.jansi#jansi;1.4 ...
[info] Done updating.
...
[info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
> sonatypeStagingRepositoryProfiles
[info] Nexus repository URL: https://localhost:8081/nexus/service/local
[info] sonatypeProfileName = Example Staging Profile
[info] Reading staging repository profiles...
[info] Staging repository profiles (sonatypeProfileName:Example Staging Profile):
[info] [example_staging_profile-1005] status:closed, profile:Example Staging Profile(12aadc320d178f)
> sonatypeRelease example_staging_profile-1005
[info] Nexus repository URL: https://localhost:8081/nexus/service/local
[info] sonatypeProfileName = Example Staging Profile
[info] Reading staging repository profiles...
[info] Repository example_staging_profile-1005 is already closed
[info] Activity open started:2016-01-04T10:55:21.775-08:00, stopped:2016-01-04T10:55:21.820-08:00
[info] repositoryCreated: id:example_staging_profile-1005, user:rouquett, ip:137.79.22.153
[info] Activity close started:2016-01-04T11:05:13.610-08:00, stopped:2016-01-04T11:05:13.659-08:00
[info]      email: to:Nicolas.F.Rouquette@jpl.nasa.gov
[info] repositoryClosed: id:example_staging_profile-1005
[info] Closed successfully
[info] Reading staging profiles...
[info] Promoting staging repository [example_staging_profile-1005] status:closed, profile:Example Staging Profile(12aadc320d178f)
[info] Activity release started:2016-01-04T11:08:20.954-08:00, stopped:2016-01-04T11:08:21.000-08:00
[info]   Evaluate: id:nx-internal-ruleset, rule:RepositoryWritePolicy
[info]   Evaluate: RepositoryWritePolicy
[info]     Passed: RepositoryWritePolicy
[info]     Passed: id:nx-internal-ruleset
[info]  copyItems: source:example_staging_profile-1005, target:jpl.beta.releases
[info]      email: to:Nicolas.F.Rouquette@jpl.nasa.gov
[info] repositoryReleased: id:example_staging_profile-1005, target:jpl.beta.releases
[info] Promoted successfully
[info] Dropping staging repository [example_staging_profile-1005] status:released, profile:Example Staging Profile(12aadc320d178f)
[info] Dropped successfully: example_staging_profile-1005
[info] Set current project to operations (in build file:.../sbt-sonatype/src/sbt-test/sbt-sonatype/operations/)
> 
```