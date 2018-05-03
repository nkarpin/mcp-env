ssh_opt = " -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
def remote_ssh_cmd(server, command){
  ssh_user = "mos-scale-jenkins"
  writeFile file: "/tmp/cmd-tmp.sh", text: command
  sh "scp $ssh_opt /tmp/cmd-tmp.sh $ssh_user@$server:/tmp/cmd-tmp.sh"
  sh "ssh $ssh_opt $ssh_user@$server bash -xe /tmp/cmd-tmp.sh"
}
node ('python') {
  currentBuild.description = STACK_NAME
  // Checkout scm specified in job configuration
  checkout scm
  // Configure OpenStack credentials and command
  withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'openstack-devcloud-credentials',
      usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD']]) {
          env.OS_USERNAME = OS_USERNAME
          env.OS_PASSWORD = OS_PASSWORD
          env.OS_PROJECT_NAME = OS_PROJECT_NAME
  }
  openstack = "set +x; venv/bin/openstack "
  vpython = "venv/bin/python"
  report = "venv/bin/report"
  jenkins_user = "admin"
  jenkins_pass = "r00tme"
  stage ('Build venv'){
      sh "virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient junitparser git+https://github.com/dis-xcom/testrail_reporter"
  }
  stage ('Get cfg01 floating'){
    out = sh script: "$openstack stack show -f value -c outputs $STACK_NAME | jq -r .[0].output_value", returnStdout: true
    cfg01_ip = out.trim()
  }
  stage ('Prepare cluster for run rally'){
    build(job: 'prepare-tests-mcp-env',
      parameters: [
          [$class: 'StringParameterValue', name: 'REFSPEC', value: REFSPEC],
          [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME],
          [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
        ])
  }
  stage ('Run rally'){
    sh "rm -rf archive"
    sh "rm -rf artifacts"
    sh "mkdir artifacts"
    try {
      JSON = "{\"parameter\":["+
      "{\"name\":\"RUN_RALLY_TESTS\", \"value\":true},"+
      "{\"name\":\"RUN_TEMPEST_TESTS\", \"value\":false},"+
      "{\"name\":\"RUN_K8S_TESTS\", \"value\":false},"+
      "{\"name\":\"RUN_SPT_TESTS\", \"value\":false},"+
      "{\"name\":\"ACCUMULATE_RESULTS\", \"value\":true},"+
      "{\"name\":\"GENERATE_REPORT\", \"value\":false},"+
      "{\"name\":\"SALT_MASTER_CREDENTIALS\", \"value\":\"salt\"},"+
      "{\"name\":\"SALT_MASTER_URL\", \"value\":\"http://cfg01:6969\"},"+
      "{\"name\":\"TARGET_NODE\", \"value\":\"cfg01.${STACK_NAME}.local\"},"+
      "{\"name\":\"FLOATING_NETWORK\", \"value\":\"public\"},"+
      "{\"name\":\"RALLY_IMAGE\", \"value\":\"cirros-disk\"},"+
      "{\"name\":\"RALLY_FLAVOR\", \"value\":\"m1.tiny\"},"+
      "{\"name\":\"AVAILABILITY_ZONE\", \"value\":\"admin\"},"+
      "{\"name\":\"TEST_IMAGE\", \"value\":\"$TEST_IMAGE\"},"+
      "{\"name\":\"RALLY_CONFIG_REPO\", \"value\":\"$RALLY_CONFIG_REPO\"},"+
      "{\"name\":\"RALLY_CONFIG_BRANCH\", \"value\":\"$RALLY_CONFIG_BRANCH\"},"+
      "{\"name\":\"RALLY_SCENARIOS\",\"value\":\"test_config/$RALLY_SCENARIOS\"},"+
      "{\"name\":\"RALLY_TASK_ARGS_FILE\",\"value\":\"test_config/$RALLY_TASK_ARGS_FILE\"},"+
      "{\"name\":\"REPORT_DIR\",\"value\":\"\"}"+
      "]}"
      build(job: 'run-job-on-cfg01-jenkins',
        parameters: [
            [$class: 'StringParameterValue', name: 'REFSPEC', value: REFSPEC],
            [$class: 'StringParameterValue', name: 'JOB_NAME', value: 'run-rally-cfg01'],
            [$class: 'StringParameterValue', name: 'JOB_TIMEOUT', value: '8'],
            [$class: 'StringParameterValue', name: 'JOB_ATTEMPTS', value: '1'],
            [$class: 'StringParameterValue', name: 'JOB_JSON', value: JSON],
            [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME],
            [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
          ])
      report_prefix = RALLY_SCENARIOS.replaceAll("/",".")
      sh "rm -f artifacts.zip"
      // TODO need to change logic to get not last build but needed artifact
      sh "wget --auth-no-challenge -O artifacts.zip '${env.JENKINS_URL}/job/run-job-on-cfg01-jenkins/lastSuccessfulBuild/artifact/*zip*/archive.zip'"
      sh "unzip artifacts.zip"
      sh "cd archive/archive/validation_artifacts; for i in \$(ls ); do mv \$i ${report_prefix}.\$i ; done"
      sh "mv archive/archive/validation_artifacts/* artifacts/"
      sh "rm -rf archive"
      sh "$vpython ${WORKSPACE}/files/rewrite_rally_junut.py \$(ls artifacts/*.xml) /tmp/rewrited_junut.xml"
      report_cmd = "$report --verbose --testrail-url https://mirantis.testrail.com --testrail-user 'mos-scale-jenkins@mirantis.com' "+
               " --testrail-password 'Qwerty1234' --testrail-project 'Mirantis Cloud Platform' --testrail-milestone 'MCP1.1' "+
               "--testrail-suite 'Rally-light' --testrail-plan-name 'Rally-light' --env 'Dev cloud' "+
               "--xunit-name-template '{classname}.{methodname}' --testrail-name-template '{title}' "+
               "/tmp/rewrited_junut.xml"
      try {
        if (params.REPORT_RALLY_RESULTS_TO_TESTRAIL){
           sh "$report_cmd"
        }
      } catch (Exception e) {
        println "Can't add results to testrail !!!"
      }
    } finally {
        archiveArtifacts artifacts: 'artifacts/*'
        junit 'artifacts/*.xml'
        if (params.REPORT_RALLY_RESULTS_TO_SCALE){
          sshagent (credentials: ['mcp-scale-jenkins']) {
            sh "D=/var/lib/kube-volumes/nginx-reports/\$(date +%Y-%m-%d_%H-%M-%S); "+
               "ssh $ssh_opt root@infra-k8s.mcp-scale.mirantis.net mkdir \$D; scp $ssh_opt artifacts/* root@infra-k8s.mcp-scale.mirantis.net:\$D/; "+
               "mkdir -p /tmp/$BUILD_TAG; echo '[main]' > /tmp/$BUILD_TAG/job_config.txt; "+
               "echo BUILD_URL = $BUILD_URL >> /tmp/$BUILD_TAG/job_config.txt; "+
               "scp $ssh_opt /tmp/$BUILD_TAG/job_config.txt root@infra-k8s.mcp-scale.mirantis.net:\$D/; rm -rf /tmp/$BUILD_TAG"
          }
        }
    }
  }
}
