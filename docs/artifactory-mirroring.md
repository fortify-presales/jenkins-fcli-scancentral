# Mirroring Fortify tooling through JFrog Artifactory

This document describes how to route all downloads made by the Jenkins pipeline through JFrog
Artifactory, so build agents don't need direct internet access to Maven Central, npm, GitHub or
`tools.fortify.com`. It mirrors the [Nexus guide](nexus-mirroring.md); use whichever matches your
repository manager.

Throughout this document, replace `artifactory.example.com` with your Artifactory host.

## Overview

| Download | Default source | Artifactory repository | Configured via |
|---|---|---|---|
| Maven dependencies | Maven Central | `maven-virtual` (virtual → `maven-remote`) | [.mvn/settings.xml](../.mvn/settings.xml) + [.mvn/maven.config](../.mvn/maven.config) |
| `@fortify/setup` (npm) | registry.npmjs.org | `npm-virtual` (virtual → `npm-remote`) | `NPM_REGISTRY` + job-scoped `.npmrc-ci` in the [Jenkinsfile](../Jenkinsfile) |
| fcli binary | github.com | `github-remote` (generic remote) | `FCLI_BOOTSTRAP_URL` |
| Tool definitions | github.com | `fortify-local` (generic local, rewritten copy) | `TOOL_DEFINITIONS` |
| ScanCentral Client | tools.fortify.com | `fortify-tools-remote` (generic remote) | URLs inside the rewritten tool definitions |

## 1. Artifactory repositories

Create the following repositories (Administration > Repositories):

| Key | Type / package | URL | Notes |
|---|---|---|---|
| `maven-remote` | Remote / Maven | `https://repo1.maven.org/maven2/` | Proxies Maven Central. |
| `maven-virtual` | Virtual / Maven | n/a | Include `maven-remote` (and any internal Maven repos). |
| `npm-remote` | Remote / npm | `https://registry.npmjs.org/` | Proxies the public npm registry. |
| `npm-virtual` | Virtual / npm | n/a | Include `npm-remote`. |
| `github-remote` | Remote / Generic | `https://github.com/` | Serves fcli releases (`.tgz` + `.rsa_sha256`) and the original tool definitions. |
| `fortify-tools-remote` | Remote / Generic | `https://tools.fortify.com/` | Serves ScanCentral Client zips. |
| `fortify-local` | Local / Generic | n/a | Hosts the rewritten `tool-definitions.yaml.zip`. |

Resulting URLs:

| Purpose | URL |
|---|---|
| Maven | `https://artifactory.example.com/artifactory/maven-virtual/` |
| npm registry | `https://artifactory.example.com/artifactory/api/npm/npm-virtual/` |
| Generic repos | `https://artifactory.example.com/artifactory/<repo-key>/<path>` |

Recommendations:

- Grant anonymous read (or a read-only permission target for the build user) on
  `github-remote`, `fortify-tools-remote` and `fortify-local`. The content is publicly available
  anyway and anonymous read avoids embedding credentials in download URLs.
- GitHub release downloads redirect to `objects.githubusercontent.com`. Verify the remote follows
  this redirect by fetching a release asset through it once:

  ```bash
  curl -fI https://artifactory.example.com/artifactory/github-remote/fortify/fcli/releases/download/v3.28.0/fcli-linux.tgz
  ```

- Keep **Store Artifacts Locally** enabled on the remote repositories so artifacts stay
  available if the upstream is unreachable.

## 2. Jenkins credentials

| ID | Type | Purpose |
|---|---|---|
| `artifactory-credentials` | Username with password | Artifactory username and identity/access token for Maven, npm and uploading tool definitions. |

Use an identity token rather than the account password.

## 3. Maven

Update [.mvn/settings.xml](../.mvn/settings.xml) to point the mirror at Artifactory:

```xml
<mirror>
    <id>nexus</id>
    <name>Artifactory</name>
    <mirrorOf>*</mirrorOf>
    <url>https://artifactory.example.com/artifactory/maven-virtual/</url>
</mirror>
```

The `<server>` entry (id `nexus`) reads credentials from `NEXUS_USERNAME` / `NEXUS_PASSWORD`; keep
the ids matching, or rename both to `artifactory` and adjust the environment variable names.
[.mvn/maven.config](../.mvn/maven.config) applies these settings to every `mvn` invocation in this
project, including the Maven build run by ScanCentral Client during packaging (`-bt mvn`).

In the Jenkinsfile, bind `artifactory-credentials` to the variables used by `settings.xml` in the
**Build**, **Setup fcli** and **Fortify ScanCentral SAST** stages, for example:

```groovy
usernamePassword(credentialsId: 'artifactory-credentials', usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')
```

## 4. npm (`@fortify/setup`)

Set the registry in the Jenkinsfile `environment` block:

```groovy
NPM_REGISTRY = 'https://artifactory.example.com/artifactory/api/npm/npm-virtual/'
```

The **Setup fcli** stage writes a job-scoped npm config file (`$WORKSPACE/.npmrc-ci`, referenced via
`NPM_CONFIG_USERCONFIG`) with the registry and a base64-encoded `user:token` `_auth` entry scoped to
that registry. The file is created with `umask 077` and deleted in the pipeline's `post` section.
No additional Artifactory configuration is needed; Artifactory accepts basic `_auth` for npm.

## 5. fcli bootstrap

`@fortify/setup` downloads fcli from `FCLI_BOOTSTRAP_URL` (taking precedence over
`FCLI_BOOTSTRAP_VERSION`) and verifies its signature from `<url>.rsa_sha256`, served by the same
remote repository. Pin an explicit version rather than using `latest/download`, as Artifactory may
cache the resolved `latest` artifact:

```groovy
FCLI_BOOTSTRAP_URL = 'https://artifactory.example.com/artifactory/github-remote/fortify/fcli/releases/download/v3.28.0/fcli-linux.tgz'
```

To upgrade fcli, update the version in this URL.

## 6. Tool definitions and ScanCentral Client

### Why a rewritten copy is needed

fcli uses a tool definitions bundle (`tool-definitions.yaml.zip`) to determine which tool versions
exist and where to download them. Each YAML file lists versions with an absolute `downloadUrl` (for
example `https://tools.fortify.com/scancentral/Fortify_ScanCentral_Client_26.2.1_x64.zip`) and an
`rsa_sha256` signature. Proxying the zip alone is not sufficient, as fcli would still follow the
original URLs.

Fortify [explicitly supports](https://github.com/fortify/tool-definitions/blob/main/USAGE.md)
customized tool definitions, provided that:

- The structure and names of all YAML files are kept intact, and all files are included.
- Rewritten download URLs point to exact copies of the original artifacts (so signatures still
  verify). Artifactory remote repositories guarantee this.
- The customized definitions are refreshed regularly to pick up new tool versions.

### Publishing script

Run the following on a schedule (for example a daily Jenkins job with `artifactory-credentials`
bound to `ARTIFACTORY_USER` / `ARTIFACTORY_TOKEN`):

```bash
set -eu
ART=https://artifactory.example.com/artifactory
curl -fsSL -o td.zip https://github.com/fortify/tool-definitions/releases/download/v1/tool-definitions.yaml.zip
rm -rf td && mkdir td && unzip -q td.zip -d td
find td -name '*.yaml' -exec sed -i \
  -e "s#https://tools.fortify.com/#$ART/fortify-tools-remote/#g" \
  -e "s#https://github.com/#$ART/github-remote/#g" {} +
grep -rhoE 'https://[^/]+/' td | sort -u   # verify no other hosts remain
rm -f tool-definitions.yaml.zip
(cd td && zip -qr ../tool-definitions.yaml.zip .)
curl -fsS -u "$ARTIFACTORY_USER:$ARTIFACTORY_TOKEN" -T tool-definitions.yaml.zip \
  "$ART/fortify-local/tool-definitions/v1/tool-definitions.yaml.zip"
```

If the agent running this script has no internet access either, download the original zip through
the remote instead:
`$ART/github-remote/fortify/tool-definitions/releases/download/v1/tool-definitions.yaml.zip`
(set a short **Metadata Retrieval Cache Period** / artifact cache on `github-remote`, or zap the
cached file before each run, so updates are picked up).

If the `grep` output shows hosts other than `artifactory.example.com`, add corresponding remote
repositories and `sed` expressions.

### Pipeline configuration

```groovy
TOOL_DEFINITIONS = 'https://artifactory.example.com/artifactory/fortify-local/tool-definitions/v1/tool-definitions.yaml.zip'
```

The fcli `ci` action reads `TOOL_DEFINITIONS` and installs ScanCentral Client (and Debricked CLI,
if enabled) from the rewritten URLs. Optionally set `PREINSTALLED=true` to forbid any tool downloads
when tools are pre-installed on the agent.

## 7. Verification

1. Block outbound access from `build-agent` to `github.com`, `objects.githubusercontent.com`,
   `tools.fortify.com`, `registry.npmjs.org` and `repo1.maven.org` / `repo.maven.apache.org`.
2. Run the pipeline and check that:
   - The **Build** stage resolves Maven artifacts from Artifactory.
   - The **Setup fcli** stage installs `@fortify/setup` and downloads fcli from Artifactory.
   - The **Fortify ScanCentral SAST** stage installs ScanCentral Client from
     `fortify-tools-remote` and the scan completes.
3. Browse the `-remote-cache` repositories in Artifactory to confirm the cached artifacts.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `401 Unauthorized` from Maven | Credential not bound in the stage, or `<server><id>` doesn't match `<mirror><id>`. |
| `E401` / `E404` from npm | Wrong registry URL (must include `/api/npm/`), or `npm-virtual` doesn't include `npm-remote`. |
| fcli signature verification fails | Remote returned a redirect/HTML page instead of the artifact; check redirect handling and that the file wasn't altered. |
| Tool not found / version not listed | Rewritten tool definitions outdated; re-run the publishing script. |
| Downloads still go to github.com / tools.fortify.com | `TOOL_DEFINITIONS` or `FCLI_BOOTSTRAP_URL` not set, or a host was missed in the rewrite. |
