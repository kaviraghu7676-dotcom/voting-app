cd ~/voting-app

cat > Jenkinsfile <<'EOF'
pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                echo 'GitHub source code checked out by Jenkins'
            }
        }

        stage('Build and Test') {
            steps {
                sh 'java -version'
                sh 'mvn -version'
                sh 'mvn clean test package'
            }
        }

        stage('Archive WAR') {
            steps {
                archiveArtifacts artifacts: 'target/*.war',
                                 fingerprint: true
            }
        }
    }

    post {
        success {
            echo 'Voting Application build successful!'
        }
        failure {
            echo 'Build failed. Check the Jenkins console output.'
        }
    }
}
EOF
