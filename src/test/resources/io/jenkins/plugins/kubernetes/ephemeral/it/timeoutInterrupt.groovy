pipeline {
    agent {
        label 'busybox'
    }
    stages {
        stage('node version') {
            steps {
                timeout(time: 5, unit: 'SECONDS') {
                    withEphemeralContainer(image: 'node:22-alpine') {
                        echo 'sleeping'
                        sleep(time: 10)
                    }
                }
            }
        }
    }
}