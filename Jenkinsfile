@Library('fortify-pipeline') _

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
        booleanParam(name: 'ENABLE_AVIATOR_REMEDIATIONS', defaultValue: false, description: 'Audit findings, apply Aviator remediations, and push a review branch (preview)')
        booleanParam(name: 'ENABLE_CHECK_POLICY', defaultValue: false, description: 'Check the SSC policy after scans complete')
        booleanParam(name: 'EXPORT_SARIF', defaultValue: true, description: 'Export SAST results to SARIF and archive them')
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
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

        stage('Security') {
            steps {
                fortifyCi(
                    appName: params.SSC_APP_NAME,
                    issueTemplate: params.ISSUE_TEMPLATE,
                    sensorPool: params.SAST_SENSOR_POOL,
                    dastSettings: params.DAST_SETTINGS,
                    dastScan: params.ENABLE_DAST_SCAN,
                    waitForDast: params.WAIT_FOR_DAST,
                    debrickedScan: params.ENABLE_DEBRICKED_SCAN,
                    aviatorAudit: params.ENABLE_AVIATOR_AUDIT,
                    aviatorRemediations: params.ENABLE_AVIATOR_REMEDIATIONS,
                    checkPolicy: params.ENABLE_CHECK_POLICY,
                    exportSarif: params.EXPORT_SARIF,
                    npmRegistry: 'https://repo.onfortify.com/repository/npm-public/',
                    credentials: [repository: 'nexus-credentials']
                )
            }
        }
    }
}
