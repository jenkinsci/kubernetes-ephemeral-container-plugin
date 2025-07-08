pipeline {
    agent {
        label 'busybox'
    }
    stages {
        stage('node version') {
            steps {
                echo 'starting ephemeral container'
                echo "KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED=${env.KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED}"
                withEphemeralContainer(image: 'node:22-alpine') {
                    sh 'node --version'
                }
            }
        }
    }
}