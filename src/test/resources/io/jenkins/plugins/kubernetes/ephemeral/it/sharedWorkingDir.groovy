pipeline {
    agent {
        label 'busybox'
    }
    stages {
        stage('node version') {
            steps {
                sh 'echo "foo" > foo.txt'
                withEphemeralContainer(image: 'node:22-alpine') {
                    sh 'cat foo.txt'
                    sh 'echo "bar" >> foo.txt'
                    sh 'echo "snoopy" > dogs.txt'
                }
                sh 'cat foo.txt'
                sh 'cat dogs.txt'
            }
        }
    }
}