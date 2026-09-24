// Remit CI pipeline (spec section 14). Runs on the local Jenkins of ci/docker-compose.jenkins.yml,
// where every docker call goes to the dind service (DOCKER_HOST=tcp://dind:2375), so containers
// publish their ports on the host name dind. See ci/README.md.
//
// Stage order: E2E runs before API tests because the Postman collection asserts the data that
// scripts/smoke-test.sh leaves behind (five samples plus one dead-lettered FORCE_FAIL instruction).
pipeline {
  agent any

  options {
    timestamps()
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
    skipDefaultCheckout()
    timeout(time: 90, unit: 'MINUTES')
  }

  environment {
    // Secrets of the compose stack deployed by the E2E stage (masked in the log).
    MYSQL_ROOT_PASSWORD = credentials('remit-mysql-root-password')
    REMIT_INTAKE_DB_PASSWORD = credentials('remit-intake-db-password')
    REMIT_ROUTING_DB_PASSWORD = credentials('remit-routing-db-password')
    REMIT_API_DB_PASSWORD = credentials('remit-api-db-password')
    MONGO_ROOT_PASSWORD = credentials('remit-mongo-root-password')
    REMIT_MONGO_PASSWORD = credentials('remit-mongo-password')
    REMIT_SFTP_PASSWORD = credentials('remit-sftp-password')
    REMIT_JWT_SECRET = credentials('remit-jwt-secret')
    REMIT_OPERATOR_PASSWORD = credentials('remit-operator-password')
    REMIT_ADMIN_PASSWORD = credentials('remit-admin-password')
    // Published ports of the compose stack live on the dind daemon.
    REMIT_API_URL = 'http://dind:8080'
    TESTCONTAINERS_HOST_OVERRIDE = 'dind'
    MAVEN_OPTS = '-Xmx1g -Djava.awt.headless=true'
    NO_COLOR = '1'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        sh 'git log -1 --format="%h %s"'
      }
    }

    stage('Static checks') {
      steps {
        sh './mvnw -B spotless:check'
        sh 'shellcheck --version | head -n 2 && shellcheck scripts/*.sh ci/*.sh'
      }
    }

    stage('Build and test') {
      steps {
        // Unit and integration tests (Testcontainers on dind), Spotless and the JaCoCo gate.
        sh './mvnw -B verify'
      }
      post {
        always {
          junit testResults: '**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml',
            allowEmptyResults: false
          jacoco execPattern: '**/target/jacoco.exec',
            classPattern: '**/target/classes',
            sourcePattern: '**/src/main/java',
            exclusionPattern: '**/*Application.class'
        }
      }
    }

    stage('SonarQube analysis') {
      steps {
        withSonarQubeEnv('sonarqube') {
          sh './mvnw -B sonar:sonar'
        }
      }
    }

    stage('Quality gate') {
      steps {
        // SonarQube calls back through the webhook http://jenkins:8080/sonarqube-webhook/.
        timeout(time: 5, unit: 'MINUTES') {
          waitForQualityGate abortPipeline: true
        }
      }
    }

    stage('Docker build') {
      steps {
        // The tests ran in "Build and test"; the images compile the code themselves.
        sh 'scripts/build.sh --skip-tests'
      }
    }

    stage('E2E') {
      steps {
        // .env for docker compose, from the Jenkins credentials. Never printed: tracing is off.
        sh '''
          set +x
          umask 077
          secrets="MYSQL_ROOT_PASSWORD REMIT_INTAKE_DB_PASSWORD REMIT_ROUTING_DB_PASSWORD REMIT_API_DB_PASSWORD MONGO_ROOT_PASSWORD REMIT_MONGO_PASSWORD REMIT_SFTP_PASSWORD REMIT_JWT_SECRET REMIT_OPERATOR_PASSWORD REMIT_ADMIN_PASSWORD"
          pattern="^($(echo "${secrets}" | tr ' ' '|'))="
          grep -vE "${pattern}" .env.example > .env
          for key in ${secrets}; do
            printf '%s=%s\\n' "${key}" "$(printenv "${key}")" >> .env
          done
          echo "wrote .env with $(grep -c '=' .env) variables"
        '''
        sh 'docker compose down -v --remove-orphans'
        sh 'scripts/deploy.sh --timeout 300 compose'
        // --no-up: the stack was deployed above; post tears it down after the API tests.
        sh 'scripts/smoke-test.sh --no-up'
      }
    }

    stage('API tests') {
      steps {
        sh 'newman --version && scripts/newman.sh -e docker --base-url "${REMIT_API_URL}/api/v1"'
      }
      post {
        always {
          junit testResults: 'newman/junit.xml', allowEmptyResults: true
          publishHTML(target: [
            reportName: 'Newman API tests',
            reportDir: 'newman',
            reportFiles: 'report.html',
            keepAll: true,
            alwaysLinkToLastBuild: true,
            allowMissing: true
          ])
        }
      }
    }
  }

  post {
    always {
      sh '''
        if [ -f .env ]; then
          docker compose ps -a > compose-ps.txt 2>&1 || true
          docker compose logs --no-color --timestamps > compose-logs.txt 2>&1 || true
          docker compose down -v --remove-orphans || true
        fi
        docker image prune -f > /dev/null 2>&1 || true
      '''
      archiveArtifacts artifacts: 'newman/**,**/target/site/jacoco/**,compose-ps.txt,compose-logs.txt',
        allowEmptyArchive: true, fingerprint: false
    }
    cleanup {
      sh 'rm -f .env'
    }
  }
}
