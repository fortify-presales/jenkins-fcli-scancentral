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
SARIF and archives the file.

### Prerequisites

- Agent labelled `linux && java` (see `agent { label ... }` in the Jenkinsfile), with Node.js/`npx` on `PATH` and network access to npm, GitHub (fcli download) and SSC.
- Jenkins tools (Manage Jenkins > Tools): JDK named `jdk17`, Maven named `maven3`.
- Plugins: Pipeline, Credentials Binding.
- Jenkins credentials (Secret text):
  - `ssc-ci-token` – SSC CIToken
  - `sc-client-auth-token` – ScanCentral SAST client auth token
- Pipeline parameters: `SSC_URL`, `SSC_APP_NAME`, `FCLI_VERSION`, `EXPORT_SARIF`.

The SSC application version defaults to `<SSC_APP_NAME>:<BRANCH_NAME>` (or `:main` for
non-multibranch jobs).

CI behavior is controlled by the `DO_*` variables in the Jenkinsfile `environment` block
(`DO_SETUP`, `DO_SAST_SCAN`, `DO_DEBRICKED_SCAN`, `DO_WAIT`, `DO_APPVERSION_SUMMARY`,
`DO_CHECK_POLICY`, `DO_PR_COMMENT`, `DO_SAST_EXPORT`). See the
[fcli ci action docs](https://fortify.github.io/fcli/latest/generic-actions.html) for all options.
