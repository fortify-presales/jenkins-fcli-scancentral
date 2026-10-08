pipeline {
    agent { label 'linux && java' }

    tools {
        // Must match names in Manage Jenkins > Tools
        jdk 'jdk17'
        maven 'maven3'
    }

    parameters {
        string(name: 'SSC_APP_NAME', defaultValue: 'jenkins-fcli-scancentral', description: 'SSC application name')
        string(name: 'ISSUE_TEMPLATE', defaultValue: 'Prioritized High Risk Issue Template', description: 'SSC issue template name or id used when creating a new application version')
        string(name: 'SAST_SENSOR_POOL', defaultValue: '', description: 'Optional ScanCentral SAST sensor pool name or UUID; leave blank for server-side pool selection')
        string(name: 'DAST_SETTINGS', defaultValue: '', description: 'Numeric ScanCentral DAST scan-settings ID for this application (required when ENABLE_DAST_SCAN is enabled)')
        booleanParam(name: 'ENABLE_DAST_SCAN', defaultValue: false, description: 'Enable a ScanCentral DAST scan for this build')
        booleanParam(name: 'WAIT_FOR_DAST', defaultValue: false, description: 'Wait for the DAST scan to finish; requires ENABLE_DAST_SCAN')
        booleanParam(name: 'ENABLE_DEBRICKED_SCAN', defaultValue: false, description: 'Enable a Debricked SCA scan for this build')
        booleanParam(name: 'ENABLE_AVIATOR_AUDIT', defaultValue: false, description: 'Send SAST results to Aviator for auditing')
        booleanParam(name: 'ENABLE_AVIATOR_REMEDIATIONS', defaultValue: false, description: 'Apply Aviator remediations and push a branch (preview)')
        booleanParam(name: 'ENABLE_CHECK_POLICY', defaultValue: false, description: 'Check the SSC policy after scans complete')
        booleanParam(name: 'EXPORT_SARIF', defaultValue: true, description: 'Export SAST results to SARIF and archive them')
    }

    options {
        disableConcurrentBuilds()
    }

    environment {
        SSC_APPVERSION           = "${params.SSC_APP_NAME}:${env.BRANCH_NAME ?: 'main'}"
        DAST_SETTINGS            = "${params.DAST_SETTINGS}"
        PACKAGE_EXTRA_OPTS       = '-bt mvn'
        SC_CLIENT_VERSION        = 'auto'
        SETUP_EXTRA_OPTS         = "--issue-template \"${params.ISSUE_TEMPLATE}\""
        DO_SAST_EXPORT           = 'false'
        FORTIFY_SETUP            = '@fortify/setup@2'
        FCLI_BOOTSTRAP_CACHE_DIR = "${env.WORKSPACE}/.fortify-cache/fcli/bootstrap"
        FORTIFY_DATA_DIR         = "${env.WORKSPACE}/.fortify"
        FORTIFY_ENV_FILE         = "${env.WORKSPACE}/.fortify-env.sh"
        NPM_REGISTRY             = 'https://repo.onfortify.com/repository/npm-public/'
        NPM_CONFIG_USERCONFIG    = "${env.WORKSPACE}/.npmrc-ci"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-credentials', usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                    sh 'mvn -B clean verify'
                }
            }
            post {
                success {
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }
        }

        stage('Setup fcli') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-credentials', usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                    sh '''
                        set -eu
                        umask 077
                        NPM_AUTH=$(printf '%s:%s' "$NEXUS_USERNAME" "$NEXUS_PASSWORD" | base64 | tr -d '\\n')
                        {
                            echo "registry=$NPM_REGISTRY"
                            echo "//${NPM_REGISTRY#*://}:_auth=$NPM_AUTH"
                        } > "$NPM_CONFIG_USERCONFIG"
                        npx -y "$FORTIFY_SETUP" env init --tools=fcli:auto
                        npx -y "$FORTIFY_SETUP" env shell > "$FORTIFY_ENV_FILE"
                        . "$FORTIFY_ENV_FILE"
                        fcli --version
                    '''
                }
            }
        }

        stage('Fortify ScanCentral SAST') {
            steps {
                script {
                    def fcliCiOptions = []
                    def scanBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
                    def scanPolicy = scanBranch.startsWith('feature/') ? 'devops' : 'security'
                    def sensorPool = params.SAST_SENSOR_POOL?.trim()
                    if (sensorPool && !(sensorPool ==~ /[A-Za-z0-9][A-Za-z0-9 ._-]*/)) {
                        error('SAST_SENSOR_POOL must start with a letter or digit and contain only letters, digits, spaces, dots, underscores, or hyphens')
                    }
                    def inheritedScanOptions = env.SAST_SCAN_EXTRA_OPTS?.trim()
                    if (inheritedScanOptions && (inheritedScanOptions =~ /--(?:pool|sensor-pool|sargs|scan-args)(?:=|\s|$)/)) {
                        error('Remove --pool/--sensor-pool and --sargs/--scan-args from global SAST_SCAN_EXTRA_OPTS; this pipeline controls the pool and scan policy')
                    }
                    def sastScanOptions = []
                    if (inheritedScanOptions) {
                        sastScanOptions.add(inheritedScanOptions)
                    }
                    if (sensorPool) {
                        sastScanOptions.add("--pool \"${sensorPool}\"")
                    }
                    sastScanOptions.add("--sargs \"-scan-policy ${scanPolicy}\"")
                    fcliCiOptions.add("SAST_SCAN_EXTRA_OPTS=${sastScanOptions.join(' ')}")
                    def scanCredentials = [
                        string(credentialsId: 'ssc-ci-token', variable: 'SSC_TOKEN'),
                        string(credentialsId: 'sc-client-auth-token', variable: 'SC_SAST_TOKEN'),
                        // ScanCentral packaging (-bt mvn) resolves dependencies through Nexus too
                        usernamePassword(credentialsId: 'nexus-credentials', usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')
                    ]

                    if (params.ENABLE_DAST_SCAN) {
                        def dastSettingsId = params.DAST_SETTINGS?.trim()
                        if (!dastSettingsId || !(dastSettingsId ==~ /\d+/)) {
                            error('DAST_SETTINGS must be the numeric ScanCentral DAST scan-settings ID when DO_DAST_SCAN is enabled')
                        }
                        fcliCiOptions.add('DO_DAST_SCAN=true')
                    }
                    if (params.WAIT_FOR_DAST) {
                        if (!params.ENABLE_DAST_SCAN) {
                            error('WAIT_FOR_DAST requires ENABLE_DAST_SCAN')
                        }
                        fcliCiOptions.add('DO_DAST_WAIT=true')
                    }
                    if (params.ENABLE_DEBRICKED_SCAN) {
                        scanCredentials.add(string(credentialsId: 'debricked-access-token', variable: 'DEBRICKED_ACCESS_TOKEN'))
                        fcliCiOptions.add('DO_DEBRICKED_SCAN=true')
                    }
                    if (params.ENABLE_AVIATOR_AUDIT || params.ENABLE_AVIATOR_REMEDIATIONS) {
                        if (!env.AVIATOR_URL?.trim()) {
                            error('AVIATOR_URL is required when an Aviator option is enabled')
                        }
                        scanCredentials.add(string(credentialsId: 'aviator-token', variable: 'AVIATOR_TOKEN'))
                    }
                    if (params.ENABLE_AVIATOR_AUDIT) {
                        fcliCiOptions.add('DO_AVIATOR_AUDIT=true')
                    } else if (params.ENABLE_AVIATOR_REMEDIATIONS) {
                        fcliCiOptions.add('DO_AVIATOR_AUDIT=false')
                    }
                    if (params.ENABLE_AVIATOR_REMEDIATIONS) {
                        scanCredentials.add(string(credentialsId: 'git-push-token', variable: 'GIT_PUSH_TOKEN'))
                        fcliCiOptions.add('DO_AVIATOR_REMEDIATIONS=true')
                        def remediationRun = (env.BUILD_TAG ?: "build-${env.BUILD_NUMBER ?: 'unknown'}").replaceAll(/[^A-Za-z0-9_-]/, '-')
                        def remediationBranch = "aviator/remediations/${remediationRun}"
                        fcliCiOptions.add("AVIATOR_REMEDIATIONS_EXTRA_OPTS=--branch-name \"${remediationBranch}\"")
                        echo "Aviator remediation destination: ${remediationBranch}"
                    }
                    if (params.ENABLE_CHECK_POLICY) {
                        fcliCiOptions.add('DO_CHECK_POLICY=true')
                    }

                    withCredentials(scanCredentials) {
                        withEnv(fcliCiOptions) {
                            // Single quotes keep secrets out of Groovy string interpolation
                            sh '''
                                set -eu
                                . "$FORTIFY_ENV_FILE"
                                fcli action run ci
                            '''
                        }
                    }
                }
            }
        }

        stage('Export SARIF') {
            when {
                expression { params.EXPORT_SARIF }
            }
            steps {
                withCredentials([string(credentialsId: 'ssc-ci-token', variable: 'SSC_TOKEN')]) {
                    sh '''
                        set -eu
                        . "$FORTIFY_ENV_FILE"
                        fcli ssc session login --url "$SSC_URL" -t "$SSC_TOKEN"
                        trap 'fcli ssc session logout || true' EXIT
                        fcli ssc action run sarif-sast-report --av "$SSC_APPVERSION" -f fortify-sast.sarif
                    '''
                }
                recordIssues(
                    id: 'fortify-sast',
                    name: 'Fortify SAST',
                    tools: [sarif(pattern: 'fortify-sast.sarif')],
                    failOnError: true,
                    skipPublishingChecks: true
                )
            }
            post {
                success {
                    archiveArtifacts artifacts: 'fortify-sast.sarif', allowEmptyArchive: true
                }
            }
        }
    }

    post {
        always {
            dir("${env.WORKSPACE}") {
                sh 'rm -rf .fortify-cache .fortify .fortify-env.sh .npmrc-ci'
            }
        }
    }
}
