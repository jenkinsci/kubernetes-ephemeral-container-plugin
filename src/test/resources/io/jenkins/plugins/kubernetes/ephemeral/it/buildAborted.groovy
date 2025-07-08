pipeline {
    agent {
        label 'busybox'
    }
    stages {
        stage('node version') {
            steps {
                withEphemeralContainer(image: 'node:22-alpine') {
                    semaphore 'ephemeralContainer'
                }
            }
        }
    }
}