node ('python') {
  currentBuild.description = STACK_NAME
  // Configure OpenStack credentials and command
  withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'openstack-devcloud-credentials',
      usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD']]) {
          env.OS_USERNAME = OS_USERNAME
          env.OS_PASSWORD = OS_PASSWORD
          env.OS_PROJECT_NAME = OS_PROJECT_NAME
  }
  openstack = "set +x; venv/bin/openstack "
  jenkins_user = "admin"
  jenkins_pass = "r00tme"
  stage ('Build venv'){
      sh "virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient"
  }
  stage ('Get cfg01 floating'){
    out = sh script: "$openstack server list | grep cfg01.$STACK_NAME | grep -oP 'net01=.*, .*(;| )' | awk '{print \$2}' | tr -d ';'", returnStdout: true
    cfg01_ip = out.trim()
  }
  stage ('Execute job on cfg01 node'){
    out = sh script: "wget -q --auth-no-challenge --user $jenkins_user --password $jenkins_pass --output-document - 'http://$cfg01_ip:8081/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)'", returnStdout: true
    crumbl = out.trim()
    print crumbl
    // Check build status
    out = sh script: "curl -s -X GET 'http://$cfg01_ip:8081/job/$JOB_NAME/lastBuild/api/json?tree=result' --user $jenkins_user:$jenkins_pass | grep -c '404 Not Found' || true", returnStdout: true
    if (out.trim() != "1"){
      out = sh script: "curl -s -X GET 'http://$cfg01_ip:8081/job/$JOB_NAME/lastBuild/api/json?tree=result' --user $jenkins_user:$jenkins_pass | jq -r '.result'", returnStdout: true
      if (out.trim() == "null"){
        error("Job already runing !")
      }
    }
    out = sh script: "curl -s -H $crumbl -X POST http://$cfg01_ip:8081/job/$JOB_NAME/build --user $jenkins_user:$jenkins_pass --data-urlencode json='$JOB_JSON'", returnStdout: true
    print out.trim()
    sleep 60
    out = sh script: "curl -s -X GET 'http://$cfg01_ip:8081/job/$JOB_NAME/lastBuild/api/json?tree=number' --user $jenkins_user:$jenkins_pass | jq -r '.number'", returnStdout: true
    build_number = out.trim()
    print "Build number is " + build_number
  }
  stage ('Wait job'){
    timeout(time: "$JOB_TIMEOUT".toInteger(), unit: 'HOURS'){
      out = ""
      status = ""
      count = 0
      while (status != "SUCCESS" && count <= "$JOB_ATTEMPTS".toInteger() ) {
        count += 1
        print "Iteration number " + count
        while (out.trim() != "SUCCESS" && count <= "$JOB_ATTEMPTS".toInteger() ){
          out = sh script: "curl -s -X GET 'http://$cfg01_ip:8081/job/$JOB_NAME/lastBuild/api/json?tree=result' --user $jenkins_user:$jenkins_pass | jq -r '.result'", returnStdout: true
          print ("The job result is $out")
          if (out.trim() != "null" && out.trim() != "SUCCESS" && count <= "$JOB_ATTEMPTS".toInteger() ) {
            print "Job FAILURE try to start next one"
            sh "curl -s -H $crumbl -X POST http://$cfg01_ip:8081/job/$JOB_NAME/build --user $jenkins_user:$jenkins_pass --data-urlencode json='$JOB_JSON'"
            sleep 300
            status = out.trim()
            break;
          } else {
            status = out.trim()
          }
          if (status != "SUCCESS"){
            sleep 60
          }
        }
      }
      if (status != "SUCCESS"){
        error("Something wrong with jobs! After $JOB_ATTEMPTS tryes the last status is $out")
      }
    }
  }
  stage('Get job artifacts'){
    sh "rm -f ${JOB_NAME}_artifacts.zip"
    sh "rm -rf archive"
    sh "wget --auth-no-challenge -O ${JOB_NAME}_artifacts.zip  --user $jenkins_user --password $jenkins_pass 'http://$cfg01_ip:8081/job/$JOB_NAME/lastSuccessfulBuild/artifact/*zip*/archive.zip' || true"
    sh "unzip ${JOB_NAME}_artifacts.zip"
  }
  stage('Collect artifacts'){
    archiveArtifacts artifacts: 'archive/**/*'
  }
}
