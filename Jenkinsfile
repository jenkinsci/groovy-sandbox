pipeline {
    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 1, unit: 'HOURS')
    }
    agent {
        docker {
            image 'maven:3.5.0-jdk-8'
            label 'docker'
        }
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
