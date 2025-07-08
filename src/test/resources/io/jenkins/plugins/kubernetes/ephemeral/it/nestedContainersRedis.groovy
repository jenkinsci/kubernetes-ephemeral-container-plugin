pipeline {
    agent {
        label 'busybox'
    }
    stages {
        stage('node version') {
            steps {
                withEphemeralContainer(image: 'redis:8-alpine') {
                    timeout(time: 5, unit: 'SECONDS') {
                        sh '''
                           set -e
                           redis-server &
                           while ! nc -z localhost 6379; do sleep 1; done
                        '''
                    }
                    withEphemeralContainer(image: 'redis:8-alpine') {
                        sh 'redis-cli ping'
                    }
                }
            }
        }
    }
}