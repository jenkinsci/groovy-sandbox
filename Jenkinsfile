pipeline {
    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 1, unit: 'HOURS')
    }
    agent {
        label 'linux'
    }
    stages {
        stage('main') {
            steps {
                sh 'mvn -B clean verify'
            }
            post {
                success {
                    junit '**/target/surefire-reports/TEST-*.xml'
                }
            }
        }
    }
}
