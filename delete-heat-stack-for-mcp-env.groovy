/**
*
* Delete stack that were created by create-mcp-env job
*
* Expected parameters:
*   STACK_NAME                 The name of stack to delete.
*   OS_PROJECT_NAME            OpenStack project to connect to
*   OPENSTACK_ENVIRONMENT      Choose Openstack environment.
*
*
**/

common = new com.mirantis.mk.Common()

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
     heat = "$openstack stack "
     stage ('Build venv'){
         sh 'virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient'
     }
     stage ('Delete heat stack'){
       timeout(time: 20, unit: 'MINUTES'){
         out = sh script: "$heat list | awk -v stack=$STACK_NAME '{if (\$4==stack) print \$6}'", returnStdout: true
         if (out.trim() != ''){
             common.infoMsg("Deleting heat stack ${STACK_NAME}")
             out = sh script: "$heat delete --wait -y $STACK_NAME", returnStdout: true
         } else {
           common.warningMsg("Stack ${STACK_NAME} wasn't found please check job parameters.")
         }
         common.infoMsg(out)
       }
       common.infoMsg("Check keypair $STACK_NAME")
       out = sh script: "$openstack keypair list | grep ' $STACK_NAME ' || true", returnStdout: true
       if (out.trim() != ''){
          common.infoMsg("Delete keypair $STACK_NAME")
          sh "$openstack keypair delete $STACK_NAME"
       }
     }
}
