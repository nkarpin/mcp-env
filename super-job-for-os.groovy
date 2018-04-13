node ('python') {
    currentBuild.description = STACK_NAME
    // Configure OpenStack credentials and command
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'devcloud-mcp-scale',
        usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD']]) {
            env.OS_USERNAME = OS_USERNAME
            env.OS_PASSWORD = OS_PASSWORD
    }
    openstack = "set +x; venv/bin/openstack "
    reclass_tools = "venv/bin/reclass-tools"
    report = "venv/bin/report"
    git = "GIT_SSH_COMMAND='ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no' git"
    stage ('Build venv'){
        sh "virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient git+https://github.com/dis-xcom/reclass_tools git+https://github.com/dis-xcom/testrail_reporter"
    }
    stage ("Handle old heat stack") {
        if (params.DELETE_STACK){
            build(job: 'delete_heat_stack',
              parameters: [
                [$class: 'StringParameterValue', name: 'REFSPEC', value: ''],
                [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
              ])
        }
    }
    stage ('Generate model'){
      // Update cluster_domain, cluster_name, openldap_domain, openstack_compute_count,
      // from job parameters
      templateContext = readYaml text: COOKIECUTTER_TEMPLATE_CONTEXT
      tmp_template_file = WORKSPACE +"/" + JOB_NAME + ".yaml"
      sh "rm -f " + tmp_template_file
      templateContext['default_context']['cluster_domain'] = STACK_NAME + ".local"
      templateContext['default_context']['cluster_name'] = STACK_NAME
      templateContext['default_context']['openldap_domain'] = STACK_NAME + ".local"
      templateContext['default_context']['openstack_compute_count'] = COMPUTE_NODES_COUNT
      def stack_install_options = STACK_INSTALL.split(',')
      stacklight_enabled = false
      openstack_enabled = false
      kubernetes_enabled = false
      opencontrail_enabled = false
      stack_install_options.each {
        if ( it == "openstack" ) {
          openstack_enabled = true
          templateContext['default_context']['openstack_enabled'] = "True"
          templateContext['default_context']['kubernetes_enabled'] = "False"
        } else if ( it == "k8s" ) {
          kubernetes_enabled = true
          templateContext['default_context']['openstack_enabled'] = "False"
          templateContext['default_context']['kubernetes_enabled'] = "True"
        }
        if ( it == "stacklight") {
          stacklight_enabled = true
          templateContext['default_context']['stacklight_enabled'] = "True"
        } else {
          templateContext['default_context']['stacklight_enabled'] = "False"
        }
        if ( it == "opencontrail") {
          opencontrail_enabled = true
          templateContext['default_context']['opencontrail_enabled'] = "True"
          templateContext['default_context']['openstack_network_engine'] = "opencontrail"
        }
      }
      writeYaml file: tmp_template_file, data: templateContext
      COOKIECUTTER_TEMPLATE_CONTEXT = readFile tmp_template_file
      archiveArtifacts artifacts: JOB_NAME + ".yaml"
      sh "rm -f " + tmp_template_file
      print("Using context:\n" + COOKIECUTTER_TEMPLATE_CONTEXT)
      build(job: 'generate-salt-model-separated-products',
            parameters: [
              [$class: 'StringParameterValue', name: 'COOKIECUTTER_TEMPLATE_CONTEXT', value: COOKIECUTTER_TEMPLATE_CONTEXT ],
            ])
      // TODO need to change logic to get not last build but needed artifact
      sh "wget --progress=dot:mega --auth-no-challenge -O cfg01.${STACK_NAME}-config.iso --user root --password r00tme 'https://${env.JENKINS_URL}/job/generate-salt-model-separated-products/lastSuccessfulBuild/artifact/output-${STACK_NAME}/cfg01.${STACK_NAME}.local-config.iso'"
    }
    stage ('Extract config drive image'){
      sh "rm -rf /tmp/cfg01.${STACK_NAME}-config"
      sh "7z x -o/tmp/cfg01.${STACK_NAME}-config cfg01.${STACK_NAME}-config.iso"
    }
    stage ('Modify config drive image'){
      model_path = "/tmp/cfg01.${STACK_NAME}-config/model/model/classes/cluster/${STACK_NAME}"
      sh "rm cfg01.${STACK_NAME}-config.iso"
      sh "rm /tmp/cfg01.${STACK_NAME}-config/meta-data"
      // Add teams who can get ssh access
      teams_with_access = ["qa_scale",
                           "oscore_devops",
                           "tcpcloud"]
      for (team in teams_with_access) {
        sh "$reclass_tools add-key --merge classes system.openssh.server.team.$team $model_path/infra/init.yml"
      }
      if (openstack_enabled) {
        println "Setting workarounds for openstack"
        // Modify gateway network settings
        if ( !opencontrail_enabled ) {
          sh "test -d $model_path/openstack && cp -f mos-scale-infra/jenkins_job_builder/templates/super_job_for_os/cluster_settings_patch/gtw-net.yml.src $model_path/openstack/networking/gateway.yml || true"
        }
        // Modify compute yaml
        sh "mkdir $model_path/openstack/scale-ci-patch"
        sh "$reclass_tools add-key --merge classes cluster.${STACK_NAME}.openstack.scale-ci-patch.compute $model_path/openstack/compute/init.yml"
        sh "$reclass_tools add-key --merge classes system.cinder.volume.single $model_path/openstack/compute/init.yml"
        sh "$reclass_tools add-key --merge classes system.cinder.volume.notification.messagingv2 $model_path/openstack/compute/init.yml"
        sh "sed -i '/system.cinder.volume.single/d' $model_path/openstack/control.yml"
        sh "sed -i '/system.cinder.volume.notification.messagingv2/d' $model_path/openstack/control.yml"
        sh "cp -f mos-scale-infra/jenkins_job_builder/templates/super_job_for_os/cluster_settings_patch/openstack-compute.yml.src $model_path/openstack/scale-ci-patch/compute.yml"
        if (!opencontrail_enabled) {
          sh "cp -f mos-scale-infra/jenkins_job_builder/templates/super_job_for_os/cluster_settings_patch/openstack-compute-net.yml.src $model_path/openstack/networking/compute.yml"
        }
        // Move gluster servers to cid nodes
        gluster_server_params = ["system.glusterfs.server.cluster",
                                 "system.glusterfs.server.volume.salt_pki",
                                 "system.glusterfs.server.volume.glance",
                                 "system.glusterfs.server.volume.keystone"]
        for (gluster_server_param in gluster_server_params){
          sh "$reclass_tools add-key --merge classes $gluster_server_param $model_path/cicd/control/init.yml"
        }
        files_for_edit = ["cicd/control/init.yml", "infra/init.yml"]
        nodes = ['01', '02', '03']
        for (file_for_edit in files_for_edit){
          for (node in nodes){
            sh "sed -i 's/glusterfs_node${node}_address: \${_param:infra_kvm_node${node}_address}/glusterfs_node${node}_address: \${_param:cicd_control_node${node}_address}/' $model_path/$file_for_edit"
          }
        }
        files_for_edit = ["cicd/init.yml", "openstack/init.yml"]
        for (file_for_edit in files_for_edit){
          sh "test -d $model_path/cicd && sed -i 's/glusterfs_service_host: \${_param:infra_kvm_address}/glusterfs_service_host: \${_param:cicd_control_address}/' $model_path/$file_for_edit"
        }
      }
      if (kubernetes_enabled) {
        println "Setting workarounds for kubernetes"
        // put them here if you need some
      }
      // Modify opencontrail network
      if ( opencontrail_enabled ) {
        source_patch_path="mos-scale-infra/jenkins_job_builder/templates/super_job_for_os/cluster_settings_patch"
        sh "cp -f $source_patch_path/openstack-compute-opencontrail-net.yml.src $model_path/opencontrail/networking/compute.yml"
        sh "cp -f $source_patch_path/opencontrail-virtual.yml.src $model_path/opencontrail/networking/virtual.yml"
        sh "sed -i 's/opencontrail_compute_iface: .*/opencontrail_compute_iface: ens5/' $model_path/opencontrail/init.yml"
        sh "sed -i 's/opencontrail_compute_iface_mask: .*/opencontrail_compute_iface_mask: 16/' $model_path/opencontrail/init.yml"
      }
    }
    stage ('Build config drive image'){
      sh "mkisofs -o ${WORKSPACE}/cfg01.${STACK_NAME}-config.iso -V cidata -r -J --quiet /tmp/cfg01.${STACK_NAME}-config"
    }
    stage("Delete old image"){
      sh "for i in \$($openstack image list | grep cfg01-$STACK_NAME-config |  cut -f 2 -d'|'); do $openstack image delete \$i; done || true"
    }
    stage("Delete old volume"){
      timeout(10) {
        volumes_count = sh returnStdout: true, script: "$openstack volume list | grep -w cfg01-$STACK_NAME-config | wc -l"
        while (volumes_count.toInteger() > 0){
          volume_id = sh returnStdout: true, script: "$openstack volume list | grep -w cfg01-$STACK_NAME-config | cut -f 2 -d'|' | head -1"
          try {
            sh "$openstack volume delete $volume_id"
          } catch (err) {
            println "couldn't delete $volume_id, trying again"
          }
          volumes_count = sh returnStdout: true, script: "$openstack volume list | grep -w cfg01-$STACK_NAME-config | wc -l"
        }
      }
    }
    stage("Upload image"){
      sh "$openstack image create --disk-format raw --file cfg01.$STACK_NAME-config.iso cfg01-$STACK_NAME-config"
    }
    stage("Create volume"){
      sh "$openstack volume create --size 1 --image cfg01-$STACK_NAME-config --read-only cfg01-$STACK_NAME-config"
      volume_id = sh script: "$openstack volume show -f value -c id cfg01-$STACK_NAME-config", returnStdout: true
      print ("Volume cfg01-$STACK_NAME-config created. It's id is $volume_id")
    }
    stage('Collect artifatcs'){
      archiveArtifacts artifacts: "cfg01.${STACK_NAME}-config.iso"
    }
    stage("Update VMs image if needed"){
      mcpVersion = templateContext['default_context']['mcp_version']
      if (mcpVersion == 'proposed') {
        vmImageUrl = "http://ci.mcp.mirantis.net:8085/images/ubuntu-16-04-x64-mcpproposed.qcow2"
      } else {
        vmImageUrl = "http://images.mirantis.com.s3.amazonaws.com/ubuntu-16-04-x64-mcptesting.qcow2"
      }
      // Get md5sum of the image which we need
      def vmImageMd5 = sh(returnStdout: true, script: "curl -s ${vmImageUrl}.md5 | awk '{print \$1}'")
      println "it's md5 of VM image ${vmImageMd5}"
      // Find an image with needed md5 in glance
      try {
        def vmImageId = sh(returnStdout: true, script: "$openstack image list --long -f value -c ID -c Name -c Checksum | grep -w scale-ubuntu-16-04-x64-mcptesting | grep ${vmImageMd5}").split(' ')[0]
        println "Found the following images for VCP: ${vmImageId}"
      } catch (err) {
        println "Can't find images for VCP, creating a new one"
        sh "wget -q -O ./scale-ubuntu-16-04-x64-mcptesting.qcow2 ${vmImageUrl}"
        sh "md5sum ./scale-ubuntu-16-04-x64-mcptesting.qcow2"
        sh "$openstack image delete scale-ubuntu-16-04-x64-mcptesting || true"
        sh "$openstack image create --disk-format qcow2 --file ./scale-ubuntu-16-04-x64-mcptesting.qcow2 scale-ubuntu-16-04-x64-mcptesting"
        sh "rm ./scale-ubuntu-16-04-x64-mcptesting.qcow2"
      }
    }
    stage("Update day01 image if needed"){
      mcpVersion = templateContext['default_context']['mcp_version']
      if (mcpVersion == 'proposed') {
        // No versions for cfg01-day01 image due to PROD-19280. Need to change the line bellow when it fixed.
        //day01ImageUrl = "http://ci.mcp.mirantis.net:8085/images/cfg01-day01-mcpproposed.qcow2"
        day01ImageUrl = "http://ci.mcp.mirantis.net:8085/images/cfg01-day01.qcow2"
      } else {
        day01ImageUrl = "http://images.mirantis.com.s3.amazonaws.com/cfg01-day01.qcow2"
      }
      // Get md5sum of the image
      def day01ImageMd5 = sh(returnStdout: true, script: "curl -s ${day01ImageUrl}.md5 | awk '{print \$1}'")
      println "it's md5 of day01 image ${day01ImageMd5}"
      // Find an image with needed md5 in glance
      try {
        def day01ImageId = sh(returnStdout: true, script: "$openstack image list --long -f value -c ID -c Name -c Checksum | grep -w scale-cfg01-day01 | grep ${day01ImageMd5}").split(' ')[0]
        println "Found the following images for day01: ${day01ImageId}"
      } catch (err) {
        println "Can't find images for day01, creating a new one"
        sh "wget -q -O ./scale-cfg01-day01.qcow2 ${day01ImageUrl}"
        sh "md5sum ./scale-cfg01-day01.qcow2"
        sh "$openstack image delete scale-cfg01-day01 || true"
        sh "$openstack image create --disk-format qcow2 --file ./scale-cfg01-day01.qcow2 scale-cfg01-day01"
        sh "rm ./scale-cfg01-day01.qcow2"
      }
    }
    stage ('Deploy heat stack'){
      build(job: 'run-heat-stack-pipeline',
            parameters: [
              [$class: 'StringParameterValue', name: 'HEAT_ENV_FILE', value: HEAT_ENV_FILE],
              [$class: 'StringParameterValue', name: 'HEAT_TEMPLATE_FILE', value: HEAT_TEMPLATE_FILE],
              [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
              [$class: 'BooleanParameterValue', name: 'DELETE_STACK', value: Boolean.valueOf(true)],
              [$class: 'StringParameterValue', name: 'COMPUTE_NODES_COUNT', value: COMPUTE_NODES_COUNT],
              [$class: 'StringParameterValue', name: 'FLAVOR_PREFIX', value: FLAVOR_PREFIX],
              [$class: 'StringParameterValue', name: 'REFSPEC', value: HEAT_REFSPEC]
            ])
    }
    stage ('Deploy open stack'){
      job_failed = false
      deploy_settings = ""
      if (openstack_enabled){
        deploy_settings = deploy_settings + "-openstack"
      }
      if (kubernetes_enabled){
        deploy_settings = deploy_settings + "-kubernetes"
      }
      if (opencontrail_enabled){
        deploy_settings = deploy_settings + "-opencontrail"
      } else {
        if (openstack_enabled){
          deploy_settings = deploy_settings + "-ovs"
        }
      }
      if (stacklight_enabled){
        deploy_settings = deploy_settings + "-stacklight"
      }
      try {
        build(job: 'run-cicd-day01-image',
              parameters: [
                [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
                [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL]
              ])
      } catch (Exception e) {
        job_failed = true
        job_error = e
      }
      junit_report = "<?xml version='1.0' encoding='utf-8'?>"
      if (job_failed){
        junit_report = junit_report +
                       "<testsuites><testsuite errors='0' failures='1' tests='1' time='1' >" +
                       "<testcase classname='ScaleDeployment' name='cluster_deploy' time='1'> " +
                       "<failure>Deploy failured</failure>"
        } else {
        junit_report = junit_report +
                       "<testsuites><testsuite errors='0' failures='0' tests='1' time='1'>" +
                       "<testcase classname='ScaleDeployment' name='cluster_deploy' time='1' /> "
        }
      junit_report = junit_report + "</testcase></testsuite></testsuites>"
      writeFile file: "/tmp/scale_cluster_deploy_junut.xml", text: junit_report
      report_cmd = "$report --verbose --testrail-url https://mirantis.testrail.com --testrail-user 'mos-scale-jenkins@mirantis.com' "+
                   " --testrail-password 'Qwerty1234' --testrail-project 'Mirantis Cloud Platform' --testrail-milestone 'MCP1.1' "+
                   "--testrail-suite 'Scale-cluster-deploy$deploy_settings' --testrail-plan-name 'Scale-cluster-deploy$deploy_settings' --env 'Dev cloud' "+
                   "--xunit-name-template '{classname}.{methodname}' --testrail-name-template '{title}' "+
                   "/tmp/scale_cluster_deploy_junut.xml"
      try {
        if (params.REPORT_CLUSTER_DEPLOYMENT_TO_TESTRAIL){
           sh "$report_cmd"
        }
      } catch (Exception e) {
        println "Can't add results to testrail !!!"
      }
      if (job_failed) {
        throw job_error
      }
    }
    /*
    TODO Need to properly plan testing/acceptance workflow for Dev teams
    stage('Run rally tests'){
      build(job: 'run-rally',
        parameters: [
          [$class: 'StringParameterValue', name: 'REFSPEC', value: ''],
          [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
          [$class: 'StringParameterValue', name: 'TEST_IMAGE', value: 'sergeygals/rally'],
          [$class: 'StringParameterValue', name: 'RALLY_CONFIG_REPO', value: 'https://github.com/Mirantis/scale-scenarios'],
          [$class: 'StringParameterValue', name: 'RALLY_CONFIG_BRANCH', value: 'master'],
          [$class: 'StringParameterValue', name: 'RALLY_SCENARIOS', value: 'rally-scenarios-light'],
          [$class: 'StringParameterValue', name: 'RALLY_TASK_ARGS_FILE', value: 'job-params-light.yaml'],
          [$class: 'BooleanParameterValue', name: 'RALLY_SCENARIOS_RECURSIVE', value: Boolean.valueOf(true)],
        ])
    }
    */
}
