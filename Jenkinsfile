pipeline {
    agent { label 'linux && java' }

    tools {
        // Must match names in Manage Jenkins > Tools
        jdk 'jdk17'
        maven 'maven3'
    }

    parameters {
        string(name: 'SSC_URL', defaultValue: 'https://ssc.onfortify.com', description: 'Fortify SSC URL')
        string(name: 'SSC_APP_NAME', defaultValue: 'jenkins-fcli-scancentral', description: 'SSC application name')
        string(name: 'FCLI_VERSION', defaultValue: 'v3', description: "fcli version to bootstrap, e.g. 'v3', 'v3.28' or 'v3.28.0'")
        booleanParam(name: 'EXPORT_SARIF', defaultValue: true, description: 'Export SAST results to SARIF and archive them')
    }

    options {
        disableConcurrentBuilds()
    }

    environment {
        SSC_URL                  = "${params.SSC_URL}"
        SSC_APPVERSION           = "${params.SSC_APP_NAME}:${env.BRANCH_NAME ?: 'main'}"
        PACKAGE_EXTRA_OPTS       = '-bt mvn'
        SC_CLIENT_VERSION        = 'auto'
        DO_SETUP                 = 'true'
        DO_SAST_SCAN             = 'true'
        DO_DEBRICKED_SCAN        = 'false'
        DO_WAIT                  = 'true'
        DO_APPVERSION_SUMMARY    = 'true'
        DO_CHECK_POLICY          = 'false'
        DO_PR_COMMENT            = 'false'
        DO_SAST_EXPORT           = 'false'
        FORTIFY_SETUP            = '@fortify/setup@2'
        FCLI_BOOTSTRAP_VERSION   = "${params.FCLI_VERSION}"
        FCLI_BOOTSTRAP_CACHE_DIR = "${env.WORKSPACE}/.fortify-cache/fcli/bootstrap"
        FORTIFY_DATA_DIR         = "${env.WORKSPACE}/.fortify"
        FORTIFY_ENV_FILE         = "${env.WORKSPACE}/.fortify-env.sh"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B clean verify'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }
        }

        stage('Setup fcli') {
            steps {
                sh '''
                    set -eu
                    npx -y "$FORTIFY_SETUP" env init --tools=fcli:auto
                    npx -y "$FORTIFY_SETUP" env shell > "$FORTIFY_ENV_FILE"
                    . "$FORTIFY_ENV_FILE"
                    fcli --version
                '''
            }
        }

        stage('Fortify ScanCentral SAST') {
            steps {
                withCredentials([
                    string(credentialsId: 'ssc-ci-token', variable: 'SSC_TOKEN'),
                    string(credentialsId: 'sc-client-auth-token', variable: 'SC_SAST_TOKEN')
                ]) {
                    // Single quotes keep secrets out of Groovy string interpolation
                    sh '''
                        set -eu
                        . "$FORTIFY_ENV_FILE"
                        fcli action run ci
                    '''
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
                sh 'rm -rf .fortify-cache .fortify .fortify-env.sh'
            }
        }
    }
}
