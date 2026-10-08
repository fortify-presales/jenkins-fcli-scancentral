def call(Map overrides = [:]) {
    def settings = resolveSettings(overrides)
    def scanBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
    def featureBranch = scanBranch.startsWith('feature/')
    def scanPolicy = settings.scanPolicy ?: (featureBranch ? 'devops' : 'security')
    if (!(scanPolicy in ['devops', 'security', 'classic']) || (!featureBranch && scanPolicy == 'devops')) {
        error('Use Security or Classic on non-feature branches; supported policies are devops, security, and classic')
    }
    if (env.CHANGE_FORK?.trim()) {
        error('Fortify credentials must not be exposed to fork pull requests')
    }

    def appVersion = "${settings.appName}:${settings.versionName}"
    def scanOptions = []
    def inheritedOptions = env.SAST_SCAN_EXTRA_OPTS?.trim()
    if (inheritedOptions && (inheritedOptions =~ /--(?:pool|sensor-pool|sargs|scan-args)(?:=|\s|$)/)) {
        error('Remove pool/scan-argument overrides from SAST_SCAN_EXTRA_OPTS; use sensorPool and scanPolicy')
    }
    if (settings.scanTimeoutMinutes != null && inheritedOptions && (inheritedOptions =~ /--scan-timeout(?:=|\s|$)/)) {
        error('Remove --scan-timeout from SAST_SCAN_EXTRA_OPTS when supplying scanTimeoutMinutes')
    }
    if (inheritedOptions) {
        scanOptions.add(inheritedOptions)
    }
    if (settings.sensorPool) {
        scanOptions.add("--pool \"${settings.sensorPool}\"")
    }
    if (settings.scanTimeoutMinutes != null) {
        scanOptions.add("--scan-timeout=${settings.scanTimeoutMinutes}")
    }
    scanOptions.add("--sargs \"-scan-policy ${scanPolicy}\"")

    def remediationRun = (env.BUILD_TAG ?: "build-${env.BUILD_NUMBER ?: 'unknown'}").replaceAll(/[^A-Za-z0-9_-]/, '-')
    def remediationBranch = "aviator/remediations/${remediationRun}"
    def workDir = "${pwd(tmp: true)}/fortify-ci"
    def environment = [
        "SSC_URL=${settings.sscUrl}",
        "SSC_APPVERSION=${appVersion}",
        "AVIATOR_URL=${settings.aviatorUrl ?: ''}",
        "AVIATOR_APP=${settings.aviatorApp ?: settings.appName}",
        "DAST_SETTINGS=${settings.dastSettings ?: ''}",
        "PACKAGE_EXTRA_OPTS=${settings.packageExtraOpts}",
        "SC_CLIENT_VERSION=${settings.scClientVersion}",
        "SETUP_EXTRA_OPTS=--issue-template \"${settings.issueTemplate}\"",
        "SAST_SCAN_EXTRA_OPTS=${scanOptions.join(' ')}",
        'DO_SETUP=true',
        'DO_SAST_SCAN=true',
        'DO_WAIT=true',
        'DO_SAST_WAIT=true',
        'DO_APPVERSION_SUMMARY=true',
        'DO_SAST_EXPORT=false',
        'DO_DEBRICKED_EXPORT=false',
        'DO_PR_COMMENT=false',
        "DO_DAST_SCAN=${settings.dastScan}",
        "DO_DAST_WAIT=${settings.waitForDast}",
        "DO_DEBRICKED_SCAN=${settings.debrickedScan}",
        "DO_AVIATOR_AUDIT=${settings.aviatorAudit || settings.aviatorRemediations}",
        "DO_AVIATOR_REMEDIATIONS=${settings.aviatorRemediations}",
        "DO_CHECK_POLICY=${settings.checkPolicy}",
        "AVIATOR_REMEDIATIONS_ACTION=${settings.aviatorRemediations ? 'push-remediations' : ''}",
        "AVIATOR_REMEDIATIONS_EXTRA_OPTS=${settings.aviatorRemediations ? '--branch-name "' + remediationBranch + '"' : ''}",
        "FORTIFY_SETUP=${settings.setupPackage}",
        "FCLI_BOOTSTRAP_VERSION=${settings.fcliBootstrapVersion ?: ''}",
        "FCLI_BOOTSTRAP_URL=${settings.fcliBootstrapUrl ?: ''}",
        "TOOL_DEFINITIONS=${settings.toolDefinitions ?: ''}",
        "FORTIFY_CI_DIR=${workDir}",
        "FCLI_BOOTSTRAP_CACHE_DIR=${workDir}/bootstrap",
        "FORTIFY_DATA_DIR=${workDir}/data",
        "FCLI_STATE_DIR=${workDir}/state",
        "FORTIFY_ENV_FILE=${workDir}/env.sh",
        "NPM_CONFIG_USERCONFIG=${workDir}/npmrc",
        "NPM_REGISTRY=${settings.npmRegistry}",
        "FORTIFY_REPOSITORY_AUTH=${settings.credentials.repository != null}",
        "FORTIFY_SARIF_FILE=${settings.sarifFile}"
    ]
    def repositoryCredentials = settings.credentials.repository ? [
        usernamePassword(credentialsId: settings.credentials.repository, usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')
    ] : []
    def scanCredentials = repositoryCredentials + [
        string(credentialsId: settings.credentials.ssc, variable: 'SSC_TOKEN'),
        string(credentialsId: settings.credentials.scSast, variable: 'SC_SAST_TOKEN')
    ]
    if (settings.debrickedScan) {
        scanCredentials.add(string(credentialsId: settings.credentials.debricked, variable: 'DEBRICKED_ACCESS_TOKEN'))
    }
    if (settings.aviatorAudit || settings.aviatorRemediations) {
        scanCredentials.add(string(credentialsId: settings.credentials.aviator, variable: 'AVIATOR_TOKEN'))
    }
    if (settings.aviatorRemediations) {
        scanCredentials.add(string(credentialsId: settings.credentials.gitPush, variable: 'GIT_PUSH_TOKEN'))
    }

    withEnv(environment) {
        try {
            stage('Setup fcli') {
                withCredentials(repositoryCredentials) {
                    sh '''
                        set -eu
                        umask 077
                        mkdir -p "$FORTIFY_CI_DIR"
                        {
                            printf 'registry=%s\n' "$NPM_REGISTRY"
                            if [ "$FORTIFY_REPOSITORY_AUTH" = true ]; then
                                set +x
                                NPM_AUTH=$(printf '%s:%s' "$NEXUS_USERNAME" "$NEXUS_PASSWORD" | base64 | tr -d '\\n')
                                printf '//%s:_auth=%s\n' "${NPM_REGISTRY#*://}" "$NPM_AUTH"
                                unset NPM_AUTH
                            fi
                        } > "$NPM_CONFIG_USERCONFIG"
                        npx -y "$FORTIFY_SETUP" env init --tools=fcli:auto
                        npx -y "$FORTIFY_SETUP" env shell > "$FORTIFY_ENV_FILE"
                        . "$FORTIFY_ENV_FILE"
                        fcli --version
                    '''
                }
            }
            stage('Fortify ScanCentral SAST') {
                echo "Fortify target: ${appVersion}; scan policy: ${scanPolicy}"
                if (settings.aviatorRemediations) {
                    echo "Aviator remediation destination: ${remediationBranch}"
                }
                withCredentials(scanCredentials) {
                    sh '''
                        set -eu
                        . "$FORTIFY_ENV_FILE"
                        fcli action run ci
                    '''
                }
            }
            if (settings.exportSarif) {
                stage('Export SARIF') {
                    withCredentials([string(credentialsId: settings.credentials.ssc, variable: 'SSC_TOKEN')]) {
                        sh '''
                            set -eu
                            . "$FORTIFY_ENV_FILE"
                            fcli ssc session login --url "$SSC_URL" -t "$SSC_TOKEN"
                            trap 'fcli ssc session logout || true' EXIT
                            fcli ssc action run sarif-sast-report --av "$SSC_APPVERSION" -f "$FORTIFY_SARIF_FILE"
                        '''
                    }
                    recordIssues(
                        id: 'fortify-sast', name: 'Fortify SAST',
                        tools: [sarif(pattern: settings.sarifFile)],
                        failOnError: true, skipPublishingChecks: true
                    )
                    archiveArtifacts artifacts: settings.sarifFile, allowEmptyArchive: false
                }
            }
        } finally {
            dir(workDir) {
                deleteDir()
            }
        }
    }
    return [appVersion: appVersion, scanPolicy: scanPolicy,
            remediationBranch: settings.aviatorRemediations ? remediationBranch : null]
}

def resolveSettings(Map overrides) {
    def defaults = [
        appName: null, versionName: env.BRANCH_NAME ?: 'main', sscUrl: env.SSC_URL,
        issueTemplate: env.FORTIFY_ISSUE_TEMPLATE ?: 'Prioritized High Risk Issue Template',
        sensorPool: env.FORTIFY_SENSOR_POOL ?: '', scanPolicy: null, scanTimeoutMinutes: null,
        packageExtraOpts: '-bt mvn', scClientVersion: 'auto', setupPackage: '@fortify/setup@2',
        npmRegistry: env.NPM_REGISTRY ?: 'https://registry.npmjs.org/',
        fcliBootstrapVersion: env.FCLI_BOOTSTRAP_VERSION, fcliBootstrapUrl: env.FCLI_BOOTSTRAP_URL,
        toolDefinitions: env.TOOL_DEFINITIONS,
        aviatorUrl: env.AVIATOR_URL, aviatorApp: env.AVIATOR_APP,
        dastSettings: '', dastScan: false, waitForDast: false, debrickedScan: false,
        aviatorAudit: false, aviatorRemediations: false, checkPolicy: false,
        exportSarif: true, sarifFile: 'fortify-sast.sarif',
        credentials: [ssc: 'ssc-ci-token', scSast: 'sc-client-auth-token', repository: null,
                      debricked: 'debricked-access-token', aviator: 'aviator-token', gitPush: 'git-push-token']
    ]
    def unknownKeys = overrides.keySet() - defaults.keySet()
    if (unknownKeys) {
        error("Unknown fortifyCi settings: ${unknownKeys.join(', ')}")
    }
    if (overrides.containsKey('credentials') && !(overrides.credentials instanceof Map)) {
        error('credentials must be a map of Jenkins credential IDs')
    }
    def credentialOverrides = overrides.credentials ?: [:]
    if (credentialOverrides.keySet() - defaults.credentials.keySet()) {
        error('Unknown credential setting')
    }
    def settings = defaults + overrides
    settings.credentials = defaults.credentials + credentialOverrides
    ['appName', 'versionName', 'sscUrl', 'issueTemplate', 'npmRegistry', 'scClientVersion', 'setupPackage'].each { key ->
        if (!(settings[key] instanceof CharSequence) || !settings[key].toString().trim()) {
            error("${key} must be a non-empty string")
        }
        settings[key] = settings[key].toString().trim()
    }
    ['appName', 'versionName', 'issueTemplate'].each { key ->
        if (settings[key] =~ /["\\\r\n\u0000]/) {
            error("${key} contains unsupported quote, backslash, or control characters")
        }
    }
    if (settings.appName.contains(':') || settings.versionName.contains(':')) {
        error('appName and versionName must not contain the SSC delimiter :')
    }
    ['dastScan', 'waitForDast', 'debrickedScan', 'aviatorAudit', 'aviatorRemediations', 'checkPolicy', 'exportSarif'].each { key ->
        if (!(settings[key] instanceof Boolean)) {
            error("${key} must be a Boolean")
        }
    }
    if (settings.sensorPool != null && !(settings.sensorPool instanceof CharSequence)) {
        error('sensorPool must be a string')
    }
    settings.sensorPool = settings.sensorPool?.toString()?.trim()
    if (settings.sensorPool && !(settings.sensorPool ==~ /[A-Za-z0-9][A-Za-z0-9 ._-]*/)) {
        error('sensorPool must start with a letter or digit and contain only letters, digits, spaces, dots, underscores, or hyphens')
    }
    if (settings.scanTimeoutMinutes != null && !(settings.scanTimeoutMinutes.toString() ==~ /[1-9][0-9]*/)) {
        error('scanTimeoutMinutes must be a positive integer')
    }
    if (settings.waitForDast && !settings.dastScan) {
        error('waitForDast requires dastScan')
    }
    settings.dastSettings = settings.dastSettings?.toString()?.trim()
    if (settings.dastScan && !(settings.dastSettings?.toString() ==~ /[0-9]+/)) {
        error('dastSettings must be a numeric ScanCentral DAST scan-settings ID')
    }
    if ((settings.aviatorAudit || settings.aviatorRemediations) && !settings.aviatorUrl?.trim()) {
        error('aviatorUrl or AVIATOR_URL is required for Aviator')
    }
    def requiredCredentials = ['ssc', 'scSast']
    if (settings.credentials.repository != null) {
        requiredCredentials.add('repository')
    }
    if (settings.debrickedScan) {
        requiredCredentials.add('debricked')
    }
    if (settings.aviatorAudit || settings.aviatorRemediations) {
        requiredCredentials.add('aviator')
    }
    if (settings.aviatorRemediations) {
        requiredCredentials.add('gitPush')
    }
    requiredCredentials.each { key ->
        def credentialId = settings.credentials[key]
        if (!(credentialId instanceof CharSequence) || !credentialId.toString().trim()) {
            error("credentials.${key} must be a non-empty Jenkins credential ID")
        }
        settings.credentials[key] = credentialId.toString().trim()
    }
    if (!(settings.sarifFile ==~ /[A-Za-z0-9][A-Za-z0-9._-]*\.sarif/)) {
        error('sarifFile must be a workspace-local .sarif file name without directory separators')
    }
    return settings
}

return this