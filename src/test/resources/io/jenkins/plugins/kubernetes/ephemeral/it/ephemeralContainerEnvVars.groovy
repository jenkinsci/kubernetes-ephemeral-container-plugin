pipeline {
    agent {
        label 'busybox'
    }
    stages {
        stage('node version') {
            steps {
                withEphemeralContainer(image: 'node:22-alpine', envVars: [envVar(key: 'FOO', value: 'baz'), envVar(key: 'BAR', value: 'bats')]) {
                    sh 'echo foo=$FOO'
                    sh 'echo bar=$BAR'
                }
            }
        }
    }
}