# jenkins-fcli-scancentral
Java project with Jenkins build and ScanCentral integration via fcli

> **Warning:** This application is intentionally insecure and exists only to exercise
> Fortify SAST. Never deploy it or expose it on a network.

## Intentional vulnerabilities

| Vulnerability | Location |
|---|---|
| SQL Injection | `web/UserController.java` (`GET /users?name=`) |
| Command Injection | `web/CommandController.java` (`GET /ping?host=`) |
| Path Traversal | `web/FileController.java` (`GET /files?name=`) |
| Reflected XSS | `web/GreetingController.java` (`GET /greet?name=`) |
| XXE | `web/XmlController.java` (`POST /xml`) |
| Insecure Deserialization | `web/DeserializationController.java` (`POST /deserialize`) |
| SSRF | `web/FetchController.java` (`GET /fetch?url=`) |
| Open Redirect | `web/RedirectController.java` (`GET /redirect?target=`) |
| Log Forging | `web/AuthController.java` (`POST /login`) |
| Weak Hash (MD5), Weak Cipher (DES/ECB), Insecure Randomness, Hard-coded Password/Key | `util/CryptoUtil.java` |
| Hard-coded datasource password | `src/main/resources/application.properties` |

## Build locally

```bash
mvn -B clean verify
mvn spring-boot:run
```

## Jenkins pipeline

The [Jenkinsfile](Jenkinsfile) builds the app with Maven, bootstraps fcli using
[`@fortify/setup`](https://github.com/fortify/fortify-setup-js/blob/main/USAGE.md), and runs
`fcli action run ci` (the generic CI action, which targets SSC because `SSC_URL`/`SSC_TOKEN`
are set). It creates the SSC application version if needed, packages
the source with ScanCentral Client (`-bt mvn`), submits a ScanCentral SAST scan, waits for
completion and prints an application version summary. Optionally it exports results to
SARIF, publishes findings and trends through Warnings NG, and archives the file.

### Prerequisites

- Agent labelled `linux && java` (both labels), with Node.js/`npx`, Git, and standard Linux shell utilities including `base64` on `PATH`. Node.js is not installed by the Jenkinsfile. Provide network access to the Git repository, SSC, ScanCentral Controller, and dependency/tool repositories, with trusted internal TLS certificates where applicable.
- Jenkins tools (Manage Jenkins > Tools): JDK named `jdk17`, Maven named `maven3`.
- Plugins: Pipeline, Credentials Binding, and Git. Install **Warnings Next Generation** (`warnings-ng`) when `EXPORT_SARIF` is enabled (the default). A separate SARIF plugin and the Fortify Jenkins plugin are not required.
- For multibranch discovery, install Pipeline: Multibranch and the appropriate Git provider's Branch Source plugin. Configure the repository, checkout credentials, discovery rules, and webhook; use script path `Jenkinsfile`. Do not expose scan or push credentials to untrusted PR code.
- Configuration as Code is required only for the JCasC setup below.
- SSC must be integrated with a working ScanCentral SAST Controller and compatible, healthy sensors. The SSC CI identity needs permission to access/create the target application versions and retrieve results. Ensure the selected issue template exists; the pipeline default is `Prioritized High Risk Issue Template`.

### Configure Jenkins with JCasC

1. Install the Configuration as Code plugin if it is not already installed.
2. Copy the [JCasC sample](docs/jenkins-casc.yaml) to a path readable by the controller. Replace `https://ssc.example.com` and `https://aviator.example.com`, then merge the sample into your existing JCasC configuration rather than replacing unrelated controller settings.
3. Provide the referenced secret variables to the controller process through its secret manager or service/container configuration:
  - Required: `JENKINS_SSC_CI_TOKEN`, `JENKINS_SC_SAST_TOKEN`, `JENKINS_NEXUS_USERNAME`, `JENKINS_NEXUS_PASSWORD`.
  - Optional integrations: `JENKINS_DEBRICKED_ACCESS_TOKEN`, `JENKINS_AVIATOR_TOKEN`, `JENKINS_GIT_PUSH_TOKEN`.

  Keep secret values out of this repository. JCasC resolves each active secret reference when it loads, even if that integration is disabled for a build. Remove an optional credential entry from the YAML if its secret is not provisioned. The sample creates global credentials; use folder-scoped credentials when applications require different secrets.
4. Set `CASC_JENKINS_CONFIG` in the controller's service environment to the YAML file or directory. For example, a systemd drop-in can use:

  ```ini
  [Service]
  Environment="CASC_JENKINS_CONFIG=/var/lib/jenkins/casc/jenkins.yaml"
  EnvironmentFile=/etc/jenkins/casc-secrets
  ```

  Keep the environment file outside source control and restrict its permissions. For Docker or Kubernetes, mount the JCasC file into the controller and provide secret variables through its deployment secret mechanism. A repository path works only if it is accessible from the controller.
5. Restart Jenkins after changing its service environment. In **Manage Jenkins > Configuration as Code**, review load errors, then verify the credential IDs under **Manage Jenkins > Credentials**.

The sample configures `SSC_URL`, `AVIATOR_URL`, `FCLI_BOOTSTRAP_VERSION`, and the credential IDs used by the pipeline. `FCLI_BOOTSTRAP_VERSION` is optional; `@fortify/setup` defaults to the latest fcli v3 release.

### Configure Jenkins without JCasC

1. In **Manage Jenkins > System > Global properties > Environment variables**, set `SSC_URL`. Set `AVIATOR_URL` only if an Aviator feature will be enabled. `FCLI_BOOTSTRAP_VERSION` is optional and defaults to the latest fcli v3 release.
2. In **Manage Jenkins > Credentials**, create credentials with these exact IDs and types:
  - Required Secret text: `ssc-ci-token` (SSC CIToken) and `sc-client-auth-token` (ScanCentral SAST client auth token).
  - Required Username with password: `nexus-credentials` (Nexus credentials for Maven and npm).
  - Optional Secret text: `debricked-access-token`, `aviator-token`, and `git-push-token`, when enabling their corresponding features.
3. Verify that the Pipeline can access the credential IDs and the expected environment values. Tokens are injected with `withCredentials`; do not define them as plain global environment variables.

### fcli Bootstrap and Tool Downloads

Configure these non-secret variables in **Manage Jenkins > System > Global properties >
Environment variables**, or through JCasC's global environment settings. The correct spelling
is `FCLI_BOOTSTRAP_VERSION`.

| Variable | Public-download configuration | Fully mirrored configuration |
|---|---|---|
| `FCLI_BOOTSTRAP_VERSION` | `v3.28.0` to pin this example release, or `v3` to follow the latest v3 release | Leave unset when using an explicit bootstrap URL |
| `FCLI_BOOTSTRAP_URL` | Leave unset | Internal URL of the approved fcli archive |
| `TOOL_DEFINITIONS` | Leave unset | Internal URL of the complete rewritten tool definitions ZIP |

For the Linux agent and Nexus repository names in this project's mirror guide:

```text
FCLI_BOOTSTRAP_URL=https://repo.onfortify.com/repository/github-proxy/fortify/fcli/releases/download/v3.28.0/fcli-linux.tgz
TOOL_DEFINITIONS=https://repo.onfortify.com/repository/fortify-hosted/tool-definitions/v1/tool-definitions.yaml.zip
```

These are example endpoints, not repositories provisioned by this pipeline.
`FCLI_BOOTSTRAP_URL` takes precedence over `FCLI_BOOTSTRAP_VERSION`; update the URL to upgrade
the pinned release. The archive and its `.rsa_sha256` signature must both be readable through
the mirror. The definitions ZIP must contain rewritten absolute tool download URLs pointing
to byte-identical mirrored artifacts; merely proxying the original ZIP does not redirect
tool downloads. Do not embed credentials in URLs.

The current Jenkinsfile routes npm through Nexus, but fcli and supporting tools still use
public sources unless these additional mirrors are configured. `NPM_REGISTRY` is defined
in the Jenkinsfile, so a global value does not override it. For a different repository manager,
update that setting and the Maven mirror in [.mvn/settings.xml](.mvn/settings.xml).
Follow [the Nexus guide](docs/nexus-mirroring.md) or
[the Artifactory guide](docs/artifactory-mirroring.md) for repository provisioning and validation.

### Aviator Application Onboarding

Before enabling an Aviator option, provision the Aviator application and its appropriate
quota/access using an administrative onboarding process. SSC application-version creation
does not create the Aviator application. With an authorized Aviator administrator configuration:

```bash
fcli aviator app create "jenkins-fcli-scancentral"
```

Application creation alone does not establish the required quota or user access. Keep
administrator credentials out of ordinary build jobs. Store the audit user's actual JWT
contents as the Jenkins Secret text credential `aviator-token`; do not use a `file:` prefix.
Configure `AVIATOR_URL` as a non-secret endpoint.

For `fcli action run ci`, `AVIATOR_APP` defaults to the SSC application name, without the
version suffix. For example, `jenkins-fcli-scancentral:main` maps to the Aviator application
`jenkins-fcli-scancentral`. If the name differs, set `AVIATOR_APP` in the job/folder environment
or pipeline environment block:

```groovy
AVIATOR_APP = 'your-aviator-application-name'
```

Prefer application-specific configuration over one global Aviator application for unrelated
repositories. The audit identity must be authorized for the selected application. The normal
audit operation reports a missing application rather than provisioning it. Aviator audits
process SAST findings and return audited results to SSC; they do not replace scanning or
recover findings excluded by a scan policy. The CI action waits for applicable result processing
before subsequent post-scan operations.

### Git Push Credentials for Remediations

`git-push-token` is a Git hosting access token, not an SSC or Aviator token. For GitHub/GHES,
prefer a fine-grained personal access token restricted to this repository with **Contents:
Read and write**, subject to your server's supported token types and organization policies.
Complete required SSO authorization/organization approval and set an expiration. For other
Git providers, use the equivalent repository-write permission.

Store the actual token as Jenkins **Secret text**, ID `git-push-token`. The pipeline binds
it as `GIT_PUSH_TOKEN` only when `ENABLE_AVIATOR_REMEDIATIONS` is enabled. Omit the credential
if that feature is disabled; with JCasC, also remove its entry if its external secret is absent.
Checkout credentials are configured separately in the job's SCM settings.

Aviator remediations are preview functionality and push a new branch on Jenkins, without
automatically opening a PR. PR-write permission is not needed solely for this push operation.
Repository rules must allow the automation identity to create the remediation branch.
Do not place the token in source control, plain global environment variables, logs, or chat.

### Per-Application Settings

- Set `DAST_SETTINGS` to the numeric ScanCentral DAST scan-settings ID for each application/job. Find IDs with `fcli sc-dast scan-settings list`; the setting is required only when `ENABLE_DAST_SCAN` is selected. Use the numeric ID, not a CI/CD token.
- `SSC_APP_NAME` and `DAST_SETTINGS` are application/job-specific parameters. Aviator availability and entitlements depend on tenant configuration. Aviator remediations are preview functionality; on Jenkins they push a branch but do not create a pull request.
- Jenkins uses the `nexus-credentials` credential for Maven and npm dependency resolution via `https://repo.onfortify.com`. Maven is configured through [.mvn/settings.xml](.mvn/settings.xml) and [.mvn/maven.config](.mvn/maven.config). npm (`npx @fortify/setup`) uses `NPM_REGISTRY` from the Jenkinsfile through a job-scoped `.npmrc-ci`. Locally, set `NEXUS_USERNAME`/`NEXUS_PASSWORD`.
- Pipeline parameters: `SSC_APP_NAME`, `ISSUE_TEMPLATE`, `SAST_SENSOR_POOL`, `DAST_SETTINGS`, `ENABLE_DAST_SCAN`, `WAIT_FOR_DAST`, `ENABLE_DEBRICKED_SCAN`, `ENABLE_AVIATOR_AUDIT`, `ENABLE_AVIATOR_REMEDIATIONS`, `ENABLE_CHECK_POLICY`, `EXPORT_SARIF`.

The SSC application version defaults to `<SSC_APP_NAME>:<BRANCH_NAME>` (or `:main` for
non-multibranch jobs).

The `ENABLE_*` parameters opt into features for an individual build and default to false.
Checked options are passed to fcli as `DO_*=true`; unchecked options are left unset so the
fcli CI action uses its own defaults. `WAIT_FOR_DAST` requires `ENABLE_DAST_SCAN`. If only
`ENABLE_AVIATOR_REMEDIATIONS` is selected, the pipeline sets `DO_AVIATOR_AUDIT=false` to avoid
also triggering the audit that fcli enables by default when Aviator credentials are present.
`DO_SAST_EXPORT` remains false in the Jenkinsfile because this pipeline has a separate SARIF
export stage controlled by `EXPORT_SARIF`. See the
[fcli ci action docs](https://fortify.github.io/fcli/latest/generic-actions.html) for all options.

See [docs/nexus-mirroring.md](docs/nexus-mirroring.md) or
[docs/artifactory-mirroring.md](docs/artifactory-mirroring.md) for routing all tool and dependency
downloads through Nexus or JFrog Artifactory.

### SARIF Results in Warnings NG

With `EXPORT_SARIF` enabled, the pipeline logs in to SSC, exports `fortify-sast.sarif`,
and publishes it with:

```groovy
recordIssues(
  id: 'fortify-sast',
  name: 'Fortify SAST',
  tools: [sarif(pattern: 'fortify-sast.sarif')],
  failOnError: true,
  skipPublishingChecks: true
)
```

Warnings NG supplies the SARIF parser and displays findings and trends under **Fortify SAST**
on the Jenkins build/job pages. Missing or unreadable reports fail the publication step.
The pipeline does not configure a Warnings NG quality gate or publish SCM checks; SSC policy
enforcement remains controlled by `ENABLE_CHECK_POLICY`. Configure build/artifact retention
and restrict access to security reports. Check source-path resolution and reference-build
configuration before relying on new/existing issue classifications.

The report is archived on successful completion of the export/publication stage. This stage
is skipped if an earlier stage fails, including an SSC policy failure; publishing reports
after such failures would require additional pipeline restructuring. Set `EXPORT_SARIF=false`
if Warnings NG is not installed or publication is not desired.

### Scan Routing and Coverage

Sensor pools and scan policies are independent controls. A pool selects an eligible execution
environment; a scan policy selects analysis coverage; an SSC acceptance policy decides which
results block the build. In **Build with Parameters**, set `SAST_SENSOR_POOL` to a pool name
or UUID to pass `--pool` for this run. Leave it blank to omit `--pool` and use server-side pool
selection. Leading/trailing whitespace is trimmed; accepted names start with a letter or digit
and contain only letters, digits, spaces, dots, underscores, or hyphens.

The pipeline selects scan policy using the PR source branch (`CHANGE_BRANCH`) when present,
otherwise `BRANCH_NAME`, with `main` as the fallback for non-multibranch jobs:

| Branch | Scan policy |
|---|---|
| `feature/*`, including PRs originating from those branches | `devops` |
| All other branches, including `main`, `develop`, `release/*`, and `hotfix/*` | `security` |

Feature-branch matching is case-sensitive and requires the `feature/` prefix. A PR job named
`PR-42` uses its source branch when Jenkins provides `CHANGE_BRANCH`; without source metadata
it defaults to Security unless `BRANCH_NAME` itself starts with `feature/`. Adjust the trusted
pipeline rule if your organization uses a different feature-branch naming convention.

For `feature/login` with `SAST_SENSOR_POOL=linux-standard`, the pipeline passes:

```text
SAST_SCAN_EXTRA_OPTS=--pool='linux-standard' --sargs='-scan-policy devops'
```

Unrelated inherited `SAST_SCAN_EXTRA_OPTS`, such as `--scan-timeout=60`, are preserved.
Remove `--pool`, `--sensor-pool`, `--sargs`, and `--scan-args` from global/job defaults: these
conflict with the pipeline-owned pool/policy arguments and are rejected before submission.
Move pool selection to `SAST_SENSOR_POOL`; additional SCA scan arguments would require extending
the pipeline's single `--sargs` value.

Discover available pools with `fcli sc-sast sensor-pool list` in an authenticated SSC session.
Pool names here are examples, not built-in pools. Validate supported scan-policy identifiers
and arguments against the installed SCA/ScanCentral versions. Govern routing in a trusted
Shared Library if selection depends on branch or PR metadata.

Use compatible OS/toolchain pools for remote translation and appropriate memory capacity for
large applications. The controller schedules work across eligible available sensors; do not
assume CPU-aware balancing, cross-pool fallback, or automatic infrastructure scaling. Verify
those details against your deployed version and monitor queue time and availability per pool.

A suggested coverage model is DevOps for frequent PR feedback, Security for integration and
release assurance (and sensitive/high-risk PRs), and periodic Classic assessments where broader
coverage is required. Confirm actual policy coverage for your versions. Keep comparable baselines
and consistent policies per SSC version; use separate versions for separate policy streams if
needed. A narrower scan is not evidence that all findings from a broader scan were checked.

### Optional Local Translation and Incremental Analysis

The current pipeline packages source on Jenkins and uses ScanCentral for translation/scanning.
It does not implement local translation or incremental analysis.

For local translation, install and license compatible Fortify SCA on the Jenkins agent,
translate the application, and export a mobile build session (MBS). A preceding pipeline stage
can produce the MBS and set `USE_PACKAGE` to its path, or a custom `PACKAGE_ACTION` can produce
it and set `global.package.output`. ScanCentral then performs the remote scan. Validate
SCA/sensor compatibility, resource sizing, isolated build IDs, and cleanup before adopting it.

Local translation, MBS submission, faster scan policies, and reporting only new findings are
not equivalent to incremental analysis. Scanning only changed files can miss cross-file
dataflows. True incremental support and baseline requirements must be verified against the
deployed SCA/ScanCentral versions; no incremental command is prescribed here.

### First-Run Verification

Start with optional integrations disabled and `EXPORT_SARIF=true` after installing Warnings NG.
Verify Maven resolution, fcli bootstrap/version, issue-template/application-version setup,
ScanCentral job completion and SSC processing, the **Fortify SAST** findings view, and the
archived SARIF report. Enable Aviator only after its separate onboarding is complete.
This demo application must not be deployed or exposed to enable DAST.

References: [Fortify setup](https://github.com/fortify/fortify-setup-js/blob/main/USAGE.md),
[fcli CI action](https://fortify.github.io/fcli/latest/generic-actions.html),
[Aviator application creation](https://fortify.github.io/fcli/latest/manpage/fcli-aviator-app-create.html),
[Aviator SAST audit](https://fortify.github.io/fcli/latest/manpage/fcli-aviator-ssc-audit-sast.html),
[ScanCentral scan options](https://fortify.github.io/fcli/latest/manpage/fcli-sc-sast-scan-start.html),
and [Warnings NG pipeline steps](https://www.jenkins.io/doc/pipeline/steps/warnings-ng/).
