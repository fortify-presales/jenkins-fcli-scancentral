# Mirroring Fortify tooling through Nexus

This document describes how to route all downloads made by the Jenkins pipeline through the
Nexus repository manager at `https://repo.onfortify.com`, so build agents don't need direct
internet access to Maven Central, npm, GitHub or `tools.fortify.com`.

## Overview

| Download | Default source | Nexus repository | Configured via |
|---|---|---|---|
| Maven dependencies | Maven Central | `maven-public` (group/proxy) | [.mvn/settings.xml](../.mvn/settings.xml) + [.mvn/maven.config](../.mvn/maven.config) |
| `@fortify/setup` (npm) | registry.npmjs.org | `npm-public` (group/proxy) | `NPM_REGISTRY` + job-scoped `.npmrc-ci` in the [Jenkinsfile](../Jenkinsfile) |
| fcli binary | github.com | `github-proxy` (raw proxy) | `FCLI_BOOTSTRAP_URL` |
| Tool definitions | github.com | `fortify-hosted` (raw hosted, rewritten copy) | `TOOL_DEFINITIONS` |
| ScanCentral Client | tools.fortify.com | `fortify-tools-proxy` (raw proxy) | URLs inside the rewritten tool definitions |

## 1. Nexus repositories

Create the following repositories in Nexus (Administration > Repository > Repositories):

| Name | Format / type | Remote URL | Notes |
|---|---|---|---|
| `maven-public` | maven2 group | n/a | Usually exists by default; should include a proxy of Maven Central. |
| `npm-public` | npm group/proxy | `https://registry.npmjs.org/` | Enable **npm Bearer Token Realm** (Security > Realms) for authenticated access. |
| `github-proxy` | raw proxy | `https://github.com/` | Serves fcli releases (`.tgz` + `.rsa_sha256`) and the original tool definitions. |
| `fortify-tools-proxy` | raw proxy | `https://tools.fortify.com/` | Serves ScanCentral Client zips. |
| `fortify-hosted` | raw hosted | n/a | Hosts the rewritten `tool-definitions.yaml.zip`. |

Recommendations:

- Grant anonymous read on `github-proxy`, `fortify-tools-proxy` and `fortify-hosted`; the content
  is publicly available anyway and this avoids embedding credentials in download URLs.
- GitHub release downloads redirect to `objects.githubusercontent.com`. Verify the proxy follows
  this redirect by fetching a release asset through it once, for example:

  ```bash
  curl -fI https://repo.onfortify.com/repository/github-proxy/fortify/fcli/releases/download/v3.28.0/fcli-linux.tgz
  ```

## 2. Jenkins credentials

| ID | Type | Purpose |
|---|---|---|
| `nexus-credentials` | Username with password | Nexus user or user token for Maven, npm and uploading tool definitions. |

## 3. Maven

[.mvn/settings.xml](../.mvn/settings.xml) mirrors all repositories (`mirrorOf *`) to
`https://repo.onfortify.com/repository/maven-public/` and reads credentials from the
`NEXUS_USERNAME` / `NEXUS_PASSWORD` environment variables.
[.mvn/maven.config](../.mvn/maven.config) passes `-s .mvn/settings.xml` to every `mvn` invocation
in this project, including the Maven build run by ScanCentral Client during packaging
(`-bt mvn`).

The Jenkinsfile binds `nexus-credentials` to these variables in both the **Build** and
**Fortify ScanCentral SAST** stages. For local builds, export the same variables in your shell.

## 4. npm (`@fortify/setup`)

The **Setup fcli** stage writes a job-scoped npm config file (`$WORKSPACE/.npmrc-ci`, referenced
via `NPM_CONFIG_USERCONFIG`) containing:

```ini
registry=https://repo.onfortify.com/repository/npm-public/
//repo.onfortify.com/repository/npm-public/:_auth=<base64 user:password>
```

The file is created with `umask 077` and deleted in the pipeline's `post` section.

## 5. fcli bootstrap

`@fortify/setup` downloads fcli from `FCLI_BOOTSTRAP_URL` (taking precedence over
`FCLI_BOOTSTRAP_VERSION`) and verifies its signature from `<url>.rsa_sha256`, which is served by
the same proxy. Pin an explicit version rather than using `latest/download`, as Nexus may cache
the resolved `latest` artifact:

```groovy
FCLI_BOOTSTRAP_URL = 'https://repo.onfortify.com/repository/github-proxy/fortify/fcli/releases/download/v3.28.0/fcli-linux.tgz'
```

To upgrade fcli, update the version in this URL.

## 6. Tool definitions and ScanCentral Client

### Why a rewritten copy is needed

fcli uses a tool definitions bundle (`tool-definitions.yaml.zip`) to determine which tool
versions exist and where to download them. Each YAML file lists versions with an absolute
`downloadUrl` (for example
`https://tools.fortify.com/scancentral/Fortify_ScanCentral_Client_26.2.1_x64.zip`) and an
`rsa_sha256` signature. Proxying the zip alone is not sufficient, as fcli would still follow
the original URLs.

Fortify [explicitly supports](https://github.com/fortify/tool-definitions/blob/main/USAGE.md)
customized tool definitions, provided that:

- The structure and names of all YAML files are kept intact, and all files are included.
- Rewritten download URLs point to exact copies of the original artifacts (so signatures still
  verify). Nexus proxy repositories guarantee this.
- The customized definitions are refreshed regularly to pick up new tool versions.

### Publishing script

Run the following on a schedule (for example a daily Jenkins job with `nexus-credentials`
bound to `NEXUS_USERNAME` / `NEXUS_PASSWORD`):

```bash
set -eu
NEXUS=https://repo.onfortify.com/repository
curl -fsSL -o td.zip https://github.com/fortify/tool-definitions/releases/download/v1/tool-definitions.yaml.zip
rm -rf td && mkdir td && unzip -q td.zip -d td
find td -name '*.yaml' -exec sed -i \
  -e "s#https://tools.fortify.com/#$NEXUS/fortify-tools-proxy/#g" \
  -e "s#https://github.com/#$NEXUS/github-proxy/#g" {} +
grep -rhoE 'https://[^/]+/' td | sort -u   # verify no other hosts remain
rm -f tool-definitions.yaml.zip
(cd td && zip -qr ../tool-definitions.yaml.zip .)
curl -fsS -u "$NEXUS_USERNAME:$NEXUS_PASSWORD" --upload-file tool-definitions.yaml.zip \
  "$NEXUS/fortify-hosted/tool-definitions/v1/tool-definitions.yaml.zip"
```

If the `grep` output shows hosts other than `repo.onfortify.com`, add corresponding proxy
repositories and `sed` expressions.

### Pipeline configuration

```groovy
TOOL_DEFINITIONS = 'https://repo.onfortify.com/repository/fortify-hosted/tool-definitions/v1/tool-definitions.yaml.zip'
```

The fcli `ci` action reads `TOOL_DEFINITIONS` and installs ScanCentral Client (and Debricked
CLI, if enabled) from the rewritten URLs. Optionally set `PREINSTALLED=true` to forbid any tool
downloads when tools are pre-installed on the agent.

## 7. Verification

1. Block outbound access from `build-agent` to `github.com`, `objects.githubusercontent.com`,
   `tools.fortify.com`, `registry.npmjs.org` and `repo.maven.apache.org`.
2. Run the pipeline and check that:
   - The **Build** stage resolves Maven artifacts from `repo.onfortify.com`.
   - The **Setup fcli** stage installs `@fortify/setup` and downloads fcli from
     `repo.onfortify.com`.
   - The **Fortify ScanCentral SAST** stage installs ScanCentral Client from
     `fortify-tools-proxy` and the scan completes.
3. Check the Nexus repositories to confirm the cached components.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `401 Unauthorized` from Maven | `nexus-credentials` not bound in the stage, or wrong server id (must be `nexus`). |
| `E401` / `E404` from npm | npm Bearer Token Realm disabled, or `npm-public` doesn't proxy npmjs. |
| fcli signature verification fails | Proxy returned a redirect/HTML page instead of the artifact; check redirect handling. |
| Tool not found / version not listed | Rewritten tool definitions outdated; re-run the publishing script. |
| Downloads still go to github.com / tools.fortify.com | `TOOL_DEFINITIONS` or `FCLI_BOOTSTRAP_URL` not set, or a host was missed in the rewrite. |
