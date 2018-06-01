node ('python') {
    currentBuild.description = STACK_NAME
     // Configure OpenStack credentials and command
     withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'openstack-devcloud-credentials',
         usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD'], ]) {
             env.OS_USERNAME = OS_USERNAME
             env.OS_PASSWORD = OS_PASSWORD
             env.OS_PROJECT_NAME = OS_PROJECT_NAME
             if (OPENSTACK_ENVIRONMENT == 'presales') {
               env.OS_AUTH_URL='https://lab.mirantis.com:5000/v2.0'
               env.OS_REGION_NAME='RegionOne'
               env.OS_ENDPOINT_TYPE='public'
               env.OS_IDENTITY_API_VERSION='2'
             }
     }
     openstack = "set +x; venv/bin/openstack "
     heat = "$openstack stack "
     stage ('Build venv'){
         sh "virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient"
     }
     stage ('Delete heat stack'){
       out = sh script: "$heat list | awk -v stack=$STACK_NAME '{if (\$4==stack) print \$6}'", returnStdout: true
       if (out.trim() != "" && out.trim() != "DELETE_IN_PROGRESS"  &&  out.trim() != "DELETE_COMPLETE" ){
           sh "$heat delete $STACK_NAME"
           timeout(time: 20, unit: 'MINUTES'){
               out = params.STACK_NAME
               while (out.trim() != ""){
                   out = sh script: "$heat list | awk -v stack=$STACK_NAME '{if (\$4==stack) print \$4}'", returnStdout: true
                   out2 = sh script: "$heat list | awk -v stack=$STACK_NAME '{if (\$4==stack) print \$6}'", returnStdout: true
                   print ("The stack status is $out2")
                   if (out2.trim() != "DELETE_IN_PROGRESS" && out2.trim() != "" && out2.trim() != "DELETE_COMPLETE" && out.trim() !=  "CREATE_FAILED") {
                       error("Something wrong with stack! The status is $out2")
                   }
               }
           }
       }
       print ("Check keypair $STACK_NAME")
       out = sh script: "$openstack keypair list | grep ' $STACK_NAME ' || true", returnStdout: true
       if (out.trim() != ""){
          print ("Delete keypair $STACK_NAME")
          sh "$openstack keypair delete $STACK_NAME"
       }
     }
}
