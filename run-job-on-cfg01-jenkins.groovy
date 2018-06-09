String SUCCESS = 'SUCCESS'
def sleep_time = 60

node ('python') {
  currentBuild.description = STACK_NAME
  // Configure OpenStack credentials and command
  withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'openstack-devcloud-credentials',
      usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD'], ]) {
          env.OS_USERNAME = OS_USERNAME
          env.OS_PASSWORD = OS_PASSWORD
          env.OS_PROJECT_NAME = OS_PROJECT_NAME
          if (OPENSTACK_ENVIRONMENT == 'presales') {
            env.OS_AUTH_URL = 'https://lab.mirantis.com:5000/v2.0'
            env.OS_REGION_NAME = 'RegionOne'
            env.OS_ENDPOINT_TYPE = 'public'
            env.OS_IDENTITY_API_VERSION = '2'
          }
  }
  openstack = 'set +x; venv/bin/openstack '
  jenkins_user = 'admin'
  jenkins_pass = 'r00tme'
  stage ('Build venv'){
      sh 'virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient'
  }
  stage ('Get cfg01 floating'){
    out = sh script: "$openstack stack show -f value -c outputs $STACK_NAME | jq -r .[0].output_value", returnStdout: true
    cfg01_ip = out.trim()
  }
  stage ('Execute job on cfg01 node'){
    // Check build status
    out = sh script: "curl -s -X GET 'http://$cfg01_ip:8081/job/$JOB_NAME/lastBuild/api/json?tree=result' --user $jenkins_user:$jenkins_pass | grep -c '404 Not Found' || true", returnStdout: true
    if (out.trim() != '1'){
      out = sh script: "curl -s -X GET 'http://$cfg01_ip:8081/job/$JOB_NAME/lastBuild/api/json?tree=result' --user $jenkins_user:$jenkins_pass | jq -r '.result'", returnStdout: true
      if (out.trim() == 'null'){
        error('Job already runing !')
      }
    }
    // Try to start the job and catch if need to use crumb
    out = sh script: "curl -s -X POST http://$cfg01_ip:8081/job/$JOB_NAME/build --user $jenkins_user:$jenkins_pass --data-urlencode json='$JOB_JSON' | grep -c 'Error 403 No valid crumb was included in the request' || true", returnStdout: true
    if (out.trim() != '0'){
      // Using crumb
      out = sh script: "wget -q --auth-no-challenge --user $jenkins_user --password $jenkins_pass --output-document - 'http://$cfg01_ip:8081/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)'", returnStdout: true
      crumbl = out.trim()
      out = sh script: "curl -s -H $crumbl -X POST http://$cfg01_ip:8081/job/$JOB_NAME/build --user $jenkins_user:$jenkins_pass --data-urlencode json='$JOB_JSON'", returnStdout: true
    }
    sleep sleep_time
    out = sh script: "curl -s -X GET 'http://$cfg01_ip:8081/job/$JOB_NAME/lastBuild/api/json?tree=number' --user $jenkins_user:$jenkins_pass | jq -r '.number'", returnStdout: true
    build_number = out.trim()
    print 'Build number is ' + build_number
  }
  stage ('Wait job'){
    timeout(time: "$JOB_TIMEOUT".toInteger(), unit: 'HOURS'){
      out = ''
      status = ''
      count = 0
      while (status != SUCCESS && count <= "$JOB_ATTEMPTS".toInteger() ) {
        count += 1
        print 'Iteration number ' + count
        while (out.trim() != SUCCESS && count <= "$JOB_ATTEMPTS".toInteger() ){
          out = sh script: "curl -s -X GET 'http://$cfg01_ip:8081/job/$JOB_NAME/lastBuild/api/json?tree=result' --user $jenkins_user:$jenkins_pass | jq -r '.result'", returnStdout: true
          print ("The job result is $out")
          if (out.trim() != 'null' && out.trim() != SUCCESS && count <= "$JOB_ATTEMPTS".toInteger() ) {
            print 'Job FAILURE try to start next one'
            // Need to use crumb if catched earlier
            if (binding.hasVariable('crumbl')) {
              sh "curl -s -H $crumbl -X POST http://$cfg01_ip:8081/job/$JOB_NAME/build --user $jenkins_user:$jenkins_pass --data-urlencode json='$JOB_JSON'"
            } else {
              sh "curl -s -X POST http://$cfg01_ip:8081/job/$JOB_NAME/build --user $jenkins_user:$jenkins_pass --data-urlencode json='$JOB_JSON'"
            }
            sleep 300
            status = out.trim()
            break
          } else {
            status = out.trim()
          }
          if (status != SUCCESS){
            sleep sleep_time
          }
        }
      }
      if (status != SUCCESS){
        error("Something wrong with jobs! After $JOB_ATTEMPTS tryes the last status is $out")
      }
    }
  }
  stage('Get job artifacts'){
    sh "rm -f ${JOB_NAME}_artifacts.zip"
    sh 'rm -rf archive'
    sh "wget --auth-no-challenge -O ${JOB_NAME}_artifacts.zip  --user $jenkins_user --password $jenkins_pass 'http://$cfg01_ip:8081/job/$JOB_NAME/lastSuccessfulBuild/artifact/*zip*/archive.zip' || true"
    sh "unzip ${JOB_NAME}_artifacts.zip"
  }
  stage('Collect artifacts'){
    archiveArtifacts artifacts: 'archive/**/*'
  }
}
