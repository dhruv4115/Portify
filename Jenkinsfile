// D4-B3 — declarative pipeline, repo root. Deliberately only ordinary Maven and Docker
// commands (plus the three sanctioned plugin steps: junit, jacoco, archiveArtifacts) so the
// exact same stages also run in GitHub Actions (D4-B4) — /docs/RISKS.md R8's mitigation for
// "the VM is a single point of failure".
//
// Requires: a Jenkins agent with a JDK 21 + Maven "tool" configured (or a Maven-capable
// container/label), a reachable Docker daemon (the agent's user must be in the "docker"
// group — Testcontainers in the Integration stage needs this too), and this repository
// building a webhook-triggered job so a real `git push` produces a real run (not a
// manually-started one).
pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B clean compile'
            }
        }

        stage('Unit tests') {
            steps {
                sh 'mvn -B test'
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Integration') {
            steps {
                // `mvn verify` also re-runs the Surefire (unit) phase — there is no
                // Maven-native "skip only unit tests" flag without a pom.xml property, and
                // Dev B may not edit pom.xml (CLAUDE.md). Accepted as a minor redundancy;
                // the two stages stay separately reported via their own post/junit blocks.
                // Needs a Docker daemon reachable from this agent (Testcontainers MySQL).
                sh 'mvn -B verify'
            }
            post {
                always {
                    junit '**/target/failsafe-reports/*.xml'
                }
            }
        }

        stage('Coverage') {
            steps {
                jacoco()
            }
        }

        stage('Package') {
            steps {
                sh 'mvn -B -DskipTests package'
            }
        }

        stage('Docker build') {
            steps {
                sh 'docker build -f docker/Dockerfile -t protify:${BUILD_NUMBER} .'
            }
        }

        stage('Insights service') {
            // D5-B1/D5-B3 — services/insights/ (Python FastAPI). Its own tests, its own image
            // build, entirely independent of the Java stages above so a failure here doesn't
            // block the API's own pipeline path.
            steps {
                dir('services/insights') {
                    sh '''
                        python3 -m venv .venv
                        . .venv/bin/activate
                        pip install --no-cache-dir -q -r requirements.txt
                        pytest -q
                    '''
                    sh 'docker build -t protify-insights:${BUILD_NUMBER} .'
                }
            }
        }

        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }
    }

    post {
        failure {
            echo 'Pipeline failed'
        }
        always {
            cleanWs()
        }
    }
}
