pipeline {
    agent {
        label 'busybox'
    }
    stages {
        stage('node version') {
            steps {
                echo 'starting ephemeral container'
                withEphemeralContainer(image: 'node:22-alpine') {
                    sh 'node --version'
                }
            }
        }
    }
}