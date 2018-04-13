node ('python') {
    currentBuild.description = STACK_NAME
      repo_url = "ssh://mos-scale-jenkins@gerrit.mirantis.com:29418/mirantis-mos-scale/heat-templates"
      // Configure OpenStack credentials and command
      withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'devcloud-mcp-scale',
          usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD']]) {
              env.OS_USERNAME = OS_USERNAME
              env.OS_PASSWORD = OS_PASSWORD
      }
      openstack = "set +x; venv/bin/openstack "
      heat = "$openstack stack "
      stage ('Checkout'){
          git url: repo_url, branch: 'master'
      }
      stage ('Fetch review'){
        if (params.REFSPEC != ""){
            sh "git clean -xfd"
            sh "git fetch $repo_url $REFSPEC"
            sh "git checkout FETCH_HEAD"
        }
      }
      stage ('Build venv'){
          sh "virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient"
      }
      stage ('Deploy heat stack'){
          if (params.DELETE_STACK){
              build(job: 'delete-heat-stack',
                parameters: [
                  [$class: 'StringParameterValue', name: 'REFSPEC', value: ''],
                  [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
                ])
          }
          if (params.HEAT_ENV_FILE != ""){
            out = sh script: "$openstack volume list | grep cfg01-${STACK_NAME}-config | awk '{print \$2}'", returnStdout: true
            disk_drive_id = out.trim()
            sh "sed -i 's/volume01-id/${disk_drive_id}/' template/$HEAT_TEMPLATE_FILE"
            sh "$heat create -e env/$HEAT_ENV_FILE --parameter cluster_node_count=${COMPUTE_NODES_COUNT} --parameter flavor_prefix=${FLAVOR_PREFIX} -t template/$HEAT_TEMPLATE_FILE $STACK_NAME"
          } else {
            sh "$heat create -t template/$HEAT_TEMPLATE_FILE $STACK_NAME"
          }
      }
      stage ('Wait heat stack'){
          timeout(time: 100, unit: 'MINUTES'){
          out = ""
              while (out.trim() != "CREATE_COMPLETE"){
                  out = sh script: "$heat list | awk -v stack=$STACK_NAME '{if (\$4==stack) print \$6}'", returnStdout: true
                  print ("The stack status is $out")
                  if (out.trim() != "CREATE_IN_PROGRESS" && out.trim() != "CREATE_COMPLETE") {
                      error("Something wrong with stack! The status is $out")
                  }
              }
          }
      }
}
