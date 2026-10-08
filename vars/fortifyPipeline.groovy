def call(Map configuration = [:], Closure buildSteps = null) {
    if (env.CHANGE_FORK?.trim()) {
        error('The standard Fortify pipeline must not run credentialed builds for fork pull requests')
    }
    def wrapperDefaults = [agentLabel: 'linux && java', jdkTool: 'jdk17', mavenTool: 'maven3',
                           buildCommand: 'mvn -B clean verify', artifacts: 'target/*.jar']
    def wrapper = wrapperDefaults + configuration.findAll { key, value -> wrapperDefaults.containsKey(key) }
    def fortifyOptions = configuration.findAll { key, value -> !wrapperDefaults.containsKey(key) }

    pipeline {
        agent { label wrapper.agentLabel }
        tools {
            jdk wrapper.jdkTool
            maven wrapper.mavenTool
        }
        options {
            skipDefaultCheckout(true)
            disableConcurrentBuilds()
        }
        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }
            stage('Build') {
                steps {
                    script {
                        def runBuild = {
                            if (buildSteps != null) {
                                buildSteps.call()
                            } else {
                                sh wrapper.buildCommand
                            }
                        }
                        def repositoryCredential = fortifyOptions.credentials?.repository
                        if (repositoryCredential) {
                            withCredentials([usernamePassword(credentialsId: repositoryCredential,
                                usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                                runBuild()
                            }
                        } else {
                            runBuild()
                        }
                    }
                }
                post {
                    success {
                        script {
                            if (wrapper.artifacts) {
                                archiveArtifacts artifacts: wrapper.artifacts, fingerprint: true
                            }
                        }
                    }
                }
            }
            stage('Security') {
                steps {
                    fortifyCi(fortifyOptions)
                }
            }
        }
    }
}

return this