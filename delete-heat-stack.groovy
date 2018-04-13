node ('master') {
    currentBuild.description = STACK_NAME
    timestamps(){
        repo_url = "ssh://mos-scale-jenkins@gerrit.mirantis.com:29418/mirantis-mos-scale/heat-templates"
        // Configure OpenStack credentials and command
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'devcloud-mcp-scale',
            usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD']]) {
                env.OS_USERNAME = OS_USERNAME
                env.OS_PASSWORD = OS_PASSWORD
        }
        env.OS_AUTH_URL = "https://cloud-cz.bud.mirantis.net:5000/v2.0"
        env.OS_TENANT_NAME = env.OS_USERNAME
        env.OS_REGION_NAME = "RegionOne"
        env.OS_ENDPOINT_TYPE = "publicURL"
        env.OS_IDENTITY_API_VERSION = 2
        openstack = "set +x; venv/bin/openstack "
        heat = "$openstack stack "
        stage ('Checkout'){
            git url: repo_url, branch: 'master', credentialsId: 'ece6c586-cd26-481c-92a8-ccae6bf9cf86'
        }
        stage ('Fetch review'){
            if (params.REFSPEC != ""){
                sh "git clean -xfd"
                sshagent (credentials: ['ece6c586-cd26-481c-92a8-ccae6bf9cf86']) {
                sh "git fetch $repo_url $REFSPEC"
                sh "git checkout FETCH_HEAD"
            }
          }
        }
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
       }
   }
}
