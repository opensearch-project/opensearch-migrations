def call(Map config = [:]) {
    pipeline {
        agent { label 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        stages {
            stage('Build') {
                steps {
                    script {
                        if (config.buildStep) {
                            config.buildStep()
                        } else {
                            echo 'Default Build Step'
                            // Add default build steps here
                        }
                    }
                }
            }
            stage('Test') {
                steps {
                    script {
                        if (config.testStep) {
                            config.testStep()
                        } else {
                            echo 'Default Test Step'
                            // Add default test steps here
                        }
                    }
                }
            }
            stage('Deploy') {
                steps {
                    script {
                        if (config.deployStep) {
                            config.deployStep()
                        } else {
                            echo 'Default Deploy Step'
                            // Add default deploy steps here
                        }
                    }
                }
            }
        }
    }
}
