/**
 *
 * Pipeline which creates environment from cookiecutter template
 * Pipeline stages:
 *  - Handle old heat stack
 *  - Generate model
 *  - Rebuild config drive (Extract config drive image; Modify config drive image; Build config drive image)
 *  - Delete old image
 *  - Upload image
 *  - Collect artifatcs
 *  - Update VMs images if needed
 *  - Update day01 image if needed
 *  - Deploy heat stack
 *  - Provision nodes using MAAS (Optional)
 *  - Deploy open stack
 *
 * Flow parameters:
 *   STACK_NAME                             The name of a stack which will be used with the image
 *   STACK_FULL                             Create multi KVM node heat stack
 *
 *   REFSPEC                                Gerrit review for mcp-env/pipelines repo
 *   HEAT_TEMPLATES_REFSPEC                 Gerrit review for mcp-env/heat-templates repo
 *
 *   OS_PROJECT_NAME                        OpenStack project to work within the cloud. Please specify your OS_PROJECT_NAME
 *   OS_AZ                                  OpenStack availability zone to spawn heat stack. Please specify your AZ
 *   OPENSTACK_ENVIRONMENT                  Choose target openstack environment to build environment (devcloud/presales)
 *
 *   DELETE_STACK                           Delete stack with the same name
 *   STACK_INSTALL                          Comma separated list of components to install
 *
 *   MAAS_ENABLE                            Hosts provisioning using MAAS
 *
 *   COMPUTE_BUNCH                          Create Heat stack with CMP bunch
 *   FLAVOR_PREFIX                          Flavor to use for environment (dev/compact)
 *   COMPUTE_NODES_COUNT                    The number of compute nodes to add to env
 *
 * Test parameters:
 *   RUN_TESTS                              Would you like to run tests against deployed environment? By default true.
 *   REPORT_CLUSTER_DEPLOYMENT_TO_TESTRAIL  Would you like to send test deployment report to TestRail?
 *   REPORT_RALLY_RESULTS_TO_TESTRAIL       Would you like to publish rally results to TestRail?
 *   REPORT_RALLY_RESULTS_TO_SCALE          Would you like to publish rally results to http://infra-k8s.mcp-scale.mirantis.net:8888/?
 *
 **/

def common = new com.mirantis.mk.Common()

def runTests = true
if (common.validInputParam('RUN_TESTS')){
  runTests = RUN_TESTS.toBoolean()
}

node ('python') {
  currentBuild.description = STACK_NAME
  // Checkout scm specified in job configuration
  checkout scm
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
  reclass_tools = 'venv/bin/reclass-tools'
  report = 'venv/bin/report'
  git = "GIT_SSH_COMMAND='ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no' git"
  stage ('Build venv'){
      sh 'virtualenv venv; venv/bin/pip install \"cmd2<0.9.0\" python-openstackclient python-heatclient git+https://github.com/dis-xcom/reclass_tools git+https://github.com/dis-xcom/testrail_reporter'
  }
  stage ('Generate model'){
    // Update cluster_domain, cluster_name, openldap_domain, openstack_compute_count,
    // from job parameters
    templateContext = readYaml text: COOKIECUTTER_TEMPLATE_CONTEXT
    tmp_template_file = WORKSPACE + '/' + JOB_NAME + '.yaml'
    sh 'rm -f ' + tmp_template_file
    templateContext['default_context']['cluster_domain'] = STACK_NAME + '.local'
    templateContext['default_context']['cluster_name'] = STACK_NAME
    templateContext['default_context']['openldap_domain'] = STACK_NAME + '.local'
    templateContext['default_context']['openstack_compute_count'] = COMPUTE_NODES_COUNT
    def stack_install_options = STACK_INSTALL.split(',')
    openstack_enabled = false
    kubernetes_enabled = false
    opencontrail_enabled = false
    templateContext['default_context']['opencontrail_enabled'] = 'False'
    stacklight_enabled = false
    templateContext['default_context']['stacklight_enabled'] = 'False'
    cicd_enabled = false
    templateContext['default_context']['cicd_enabled'] = 'False'
    stack_install_options.each {
      switch ( it ) {
        case 'openstack':
          openstack_enabled = true
          templateContext['default_context']['openstack_enabled'] = 'True'
          templateContext['default_context']['kubernetes_enabled'] = 'False'
          templateContext['default_context']['platform'] = 'openstack_enabled'
          templateContext['default_context']['public_host'] = "\${_param:openstack_proxy_address}"
          break
        case 'k8s':
          kubernetes_enabled = true
          templateContext['default_context']['kubernetes_enabled'] = 'True'
          templateContext['default_context']['openstack_enabled'] = 'False'
          templateContext['default_context']['platform'] = 'kubernetes_enabled'
          templateContext['default_context']['public_host'] = "\${_param:infra_config_address}"
          break
        case 'stacklight':
          stacklight_enabled = true
          templateContext['default_context']['stacklight_enabled'] = 'True'
          break
        case 'cicd':
          cicd_enabled = true
          templateContext['default_context']['cicd_enabled'] = 'True'
          break
        case 'opencontrail':
          opencontrail_enabled = true
          templateContext['default_context']['opencontrail_enabled'] = 'True'
          templateContext['default_context']['openstack_network_engine'] = 'opencontrail'
          break
      }
    }
    if ( MAAS_ENABLE.toBoolean() && kubernetes_enabled ) { templateContext['default_context']['kubernetes_keepalived_vip_interface'] = 'one1' }
    writeYaml file: tmp_template_file, data: templateContext
    COOKIECUTTER_TEMPLATE_CONTEXT = readFile tmp_template_file
    archiveArtifacts artifacts: JOB_NAME + '.yaml'
    sh 'rm -f ' + tmp_template_file
    print('Using context:\n' + COOKIECUTTER_TEMPLATE_CONTEXT)
    build(job: 'generate-salt-model-separated-products',
          parameters: [
            [$class: 'StringParameterValue', name: 'COOKIECUTTER_TEMPLATE_CONTEXT', value: COOKIECUTTER_TEMPLATE_CONTEXT ],
          ])
    // TODO need to change logic to get not last build but needed artifact
    sh "wget --progress=dot:mega --auth-no-challenge -O cfg01.${STACK_NAME}-config.iso '${env.JENKINS_URL}/job/generate-salt-model-separated-products/lastSuccessfulBuild/artifact/output-${STACK_NAME}/cfg01.${STACK_NAME}.local-config.iso'"
  }
  stage ('Extract config drive image'){
    sh "rm -rf /tmp/cfg01.${STACK_NAME}-config"
    sh "7z x -o/tmp/cfg01.${STACK_NAME}-config cfg01.${STACK_NAME}-config.iso"
  }
  stage ('Modify config drive image'){
    model_path = "/tmp/cfg01.${STACK_NAME}-config/model/model/classes/cluster/${STACK_NAME}"
    sh "rm cfg01.${STACK_NAME}-config.iso"
    sh "rm /tmp/cfg01.${STACK_NAME}-config/meta-data"
    // Calculation openssh_groups variable for old releases ( older then https://gerrit.mcp.mirantis.net/#/c/19109/)
    opensshGroups = templateContext['default_context']['openssh_groups'].tokenize(',')
    infraInitFile = model_path + '/infra/init.yml'
    for (openssh_group in opensshGroups) {
      neededClass = 'system.openssh.server.team.' + openssh_group
      classExists = sh script: "grep $neededClass $infraInitFile", returnStatus: true
      if (classExists == 0){
        println "Class $neededClass found in infra/init.yaml"
      } else {
        println "Class $neededClass not found in infra/init.yaml. Adding it"
        sh "$reclass_tools add-key --merge classes $neededClass $infraInitFile"
      }
    }
    // Always clone mcp-scale-jenkins user from master and add it to qa_scale ssh group. Needed for old releases (older then https://gerrit.mcp.mirantis.net/#/c/19499/)
    masterMcpScaleJenkinsUrl = "'https://gerrit.mcp.mirantis.net/gitweb?p=salt-models/reclass-system.git;a=blob_plain;f=openssh/server/team/members/mcp-scale-jenkins.yml;hb=refs/heads/master'"
    systemLevelPath = "/tmp/cfg01.${STACK_NAME}-config/model/model/classes/system"
    McpScaleFile = systemLevelPath + '/openssh/server/team/members/mcp-scale-jenkins.yml'
    QaScaleFile = systemLevelPath + '/openssh/server/team/qa_scale.yml'
    sh "curl -s ${masterMcpScaleJenkinsUrl} > ${McpScaleFile}"
    sh "$reclass_tools add-key --merge classes system.openssh.server.team.members.mcp-scale-jenkins ${QaScaleFile}"
    if (!STACK_FULL.toBoolean()) {
      println 'Setting workarounds for MAAS'
      // Modify MAAS yaml if it necessary
      if ( MAAS_ENABLE.toBoolean() ) {
        source_patch_path = "$WORKSPACE/cluster_settings_patch"
        sh "mkdir $model_path/infra/scale-ci-patch"
        sh "cp -f $source_patch_path/maas_proxy_enable.yml.src $model_path/infra/scale-ci-patch/maas_proxy_enable.yml"
        sh "cp -f $source_patch_path/machines_template.yml.src $model_path/infra/scale-ci-patch/machines_template.yml"
        sh "$reclass_tools add-key --merge classes cluster.${STACK_NAME}.infra.scale-ci-patch.maas_proxy_enable $model_path/infra/maas.yml"
        sh "$reclass_tools add-key --merge classes cluster.${STACK_NAME}.infra.scale-ci-patch.machines_template $model_path/infra/maas.yml"
        //NOTE: differents from a customer setup.
        //This step is necessary becuase we can't disable port_security on the DevCloud. We need to specify IP addresses for the nodes in MAAS
        //in another way will lost external connection for this nodes.
        sh "cp -f $source_patch_path/dhcp_snippets.yml.src $model_path/infra/scale-ci-patch/dhcp_snippets.yml"
        sh "$reclass_tools add-key --merge classes cluster.${STACK_NAME}.infra.scale-ci-patch.dhcp_snippets $model_path/infra/maas.yml"
        //NOTE: Dirrences from a cusomer envirionment
        //We are not able to provide IMPI node information as csv file before creating stack
        sh "sed -i '/machines:/d' $model_path/infra/maas.yml"
      }
      if (openstack_enabled) {
        source_patch_path = "$WORKSPACE/cluster_settings_patch"
        println 'Setting workarounds for openstack'
        // Modify gateway network settings
        if ( !opencontrail_enabled || !MAAS_ENABLE.toBoolean() ) {
          sh "test -d $model_path/openstack && cp -f $source_patch_path/gtw-net.yml.src $model_path/openstack/networking/gateway.yml || true"
        }
        // Modify compute yaml
        // Workaround for PROD-20257 to set up LVM on compute nodes as a backend for Cinder
        sh "mkdir $model_path/openstack/scale-ci-patch"
        sh "$reclass_tools add-key --merge classes cluster.${STACK_NAME}.openstack.scale-ci-patch.compute $model_path/openstack/compute/init.yml"
        sh "$reclass_tools add-key --merge classes system.cinder.volume.single $model_path/openstack/compute/init.yml"
        sh "$reclass_tools add-key --merge classes system.cinder.volume.notification.messagingv2 $model_path/openstack/compute/init.yml"
        sh "sed -i '/system.cinder.volume.single/d' $model_path/openstack/control.yml"
        sh "sed -i '/system.cinder.volume.notification.messagingv2/d' $model_path/openstack/control.yml"
        sh "cp -f $source_patch_path/openstack-compute.yml.src $model_path/openstack/scale-ci-patch/compute.yml"
        if (!opencontrail_enabled || !MAAS_ENABLE.toBoolean() ) {
          sh "cp -f $source_patch_path/openstack-compute-net.yml.src $model_path/openstack/networking/compute.yml"
        }
        if ( MAAS_ENABLE.toBoolean() ){
          sh "cp -f $source_patch_path/openstack-compute-maas-net.yml.src $model_path/openstack/networking/compute.yml"
          if ( !opencontrail_enabled ){
            sh "cp -f $source_patch_path/openstack-gateway-maas-net.yml.src $model_path/openstack/networking/gateway.yml"
          }
        }
        // Modify kvm nodes
        if ( MAAS_ENABLE.toBoolean() ){
          sh "cp -f $source_patch_path/openstack-kvm-maas-net.yml.src $model_path/infra/networking/kvm.yml"
        }
        else {
          sh "cp -f $source_patch_path/openstack-kvm-net.yml.src $model_path/infra/networking/kvm.yml"
        }
        sh "sed -i '/system.salt.control.virt/d' $model_path/infra/kvm.yml"
        sh "sed -i '/system.salt.control.cluster.openstack_control_cluster/d' $model_path/infra/kvm.yml"
        sh "sed -i '/system.salt.control.cluster.openstack_proxy_cluster/d' $model_path/infra/kvm.yml"
        sh "sed -i '/system.salt.control.cluster.openstack_database_cluster/d' $model_path/infra/kvm.yml"
        sh "sed -i '/system.salt.control.cluster.openstack_message_queue_cluster/d' $model_path/infra/kvm.yml"
      }
      if (kubernetes_enabled) {
        println 'Setting workarounds for kubernetes'
        // useless default networking
        sh "sed -i 's/\\(^- cluster.${STACK_NAME}.kubernetes.networking.compute\\)/#\\1/' $model_path/kubernetes/compute.yml"
        // change keepalived VRID of K8S VIP
        if (templateContext['default_context']['k8s_keepalived_vip_vrid']) {
          def k8s_keepalived_vip_vrid = templateContext['default_context']['k8s_keepalived_vip_vrid']
          sh "$reclass_tools add-key parameters._param.keepalived_vip_virtual_router_id $k8s_keepalived_vip_vrid $model_path/kubernetes/control.yml"
        }
        // insecure API binding
        if (templateContext['default_context']['k8s_api_insecure_bind'] == 'True') {
          sh "$reclass_tools add-key parameters.kubernetes.master.apiserver.insecure_address 0.0.0.0 $model_path/kubernetes/control.yml"
        }
      }
      // Modify opencontrail network
      if ( opencontrail_enabled ) {
        source_patch_path = "$WORKSPACE/cluster_settings_patch"
        //Workaround for https://mirantis.jira.com/browse/PROD-19260
        if ( MAAS_ENABLE.toBoolean() ) {
          sh "cp -f $source_patch_path/openstack-compute-opencontrail-net-maas.yml.src $model_path/opencontrail/networking/compute.yml"
          sh "sed -i 's/opencontrail_compute_iface: .*/opencontrail_compute_iface: one3/' $model_path/opencontrail/init.yml"
        }
        else {
          sh "cp -f $source_patch_path/openstack-compute-opencontrail-net.yml.src $model_path/opencontrail/networking/compute.yml"
          sh "sed -i 's/opencontrail_compute_iface: .*/opencontrail_compute_iface: ens5/' $model_path/opencontrail/init.yml"
        }
        sh "cp -f $source_patch_path/opencontrail-virtual.yml.src $model_path/opencontrail/networking/virtual.yml"
        sh "sed -i 's/opencontrail_compute_iface_mask: .*/opencontrail_compute_iface_mask: 16/' $model_path/opencontrail/init.yml"
        sh "sed -i 's/keepalived_vip_interface: eth1/keepalived_vip_interface: eth0/' $model_path/opencontrail/analytics.yml"
        sh "sed -i 's/keepalived_vip_interface: eth1/keepalived_vip_interface: eth0/' $model_path/opencontrail/control.yml"
      }
    }
  }
  stage ('Build config drive image'){
    sh "mkisofs -o ${WORKSPACE}/cfg01.${STACK_NAME}-config.iso -V cidata -r -J --quiet /tmp/cfg01.${STACK_NAME}-config"
  }
  stage('Delete old image'){
    sh "for i in \$($openstack image list | grep -w cfg01-$STACK_NAME-config |  cut -f 2 -d'|'); do $openstack image delete \$i; done || true"
  }
  stage('Upload image'){
    sh "$openstack image create --disk-format raw --file cfg01.$STACK_NAME-config.iso cfg01-$STACK_NAME-config"
  }
  stage('Collect artifatcs'){
    archiveArtifacts artifacts: "cfg01.${STACK_NAME}-config.iso"
  }
  stage('Update VMs images if needed'){
    mcpVersion = templateContext['default_context']['mcp_version']
    if (mcpVersion == '') {
      mcpVersion = 'testing'
    }
    vcpImages = ['ubuntu-16-04-x64-mcp',
                 'ubuntu-14-04-x64-mcp', ]
    for (vcpImage in vcpImages) {
      vmImageUrl = "http://ci.mcp.mirantis.net:8085/images/${vcpImage}${mcpVersion}.qcow2"
      // Get md5sum of the image which we need
      def vmImageMd5 = sh(returnStdout: true, script: "curl -s ${vmImageUrl}.md5 | awk '{print \$1}'")
      println "it's md5 of VM image ${vmImageMd5}"
      // Find an image with needed md5 in glance
      try {
        def vmImageId = sh(returnStdout: true, script: "$openstack image list --long -f value -c ID -c Name -c Checksum | grep -w scale-${vcpImage}${mcpVersion} | grep ${vmImageMd5}").split(' ')[0]
        println "Found the following images for VCP: ${vmImageId}"
      } catch (err) {
        println "Can't find images for VCP, creating a new one"
        sh "wget -q -O ./scale-${vcpImage}${mcpVersion}.qcow2 ${vmImageUrl}"
        sh "md5sum ./scale-${vcpImage}${mcpVersion}.qcow2"
        sh "$openstack image delete scale-${vcpImage}${mcpVersion} || true"
        sh "$openstack image create --disk-format qcow2 --file ./scale-${vcpImage}${mcpVersion}.qcow2 scale-${vcpImage}${mcpVersion}"
        sh "rm ./scale-${vcpImage}${mcpVersion}.qcow2"
      }
    }
  }
  stage('Update day01 image if needed'){
    mcpVersion = templateContext['default_context']['mcp_version']
    if (mcpVersion == '') {
      mcpVersion = 'testing'
    }
    day01ImageUrl = "http://ci.mcp.mirantis.net:8085/images/cfg01-day01-${mcpVersion}.qcow2"
    // Get md5sum of the image
    def day01ImageMd5 = sh(returnStdout: true, script: "curl -s ${day01ImageUrl}.md5 | awk '{print \$1}'")
    println "it's md5 of day01 image ${day01ImageMd5}"
    // Find an image with needed md5 in glance
    try {
      def day01ImageId = sh(returnStdout: true, script: "$openstack image list --long -f value -c ID -c Name -c Checksum | grep -w scale-cfg01-day01-${mcpVersion} | grep ${day01ImageMd5}").split(' ')[0]
      println "Found the following images for day01: ${day01ImageId}"
    } catch (err) {
      println "Can't find images for day01, creating a new one"
      sh "wget -q -O ./scale-cfg01-day01-${mcpVersion}.qcow2 ${day01ImageUrl}"
      sh "md5sum ./scale-cfg01-day01-${mcpVersion}.qcow2"
      sh "$openstack image delete scale-cfg01-day01-${mcpVersion} || true"
      sh "$openstack image create --disk-format qcow2 --file ./scale-cfg01-day01-${mcpVersion}.qcow2 scale-cfg01-day01-${mcpVersion}"
      sh "rm ./scale-cfg01-day01-${mcpVersion}.qcow2"
    }
  }
  stage ('Deploy heat stack'){
    build(job: 'create-heat-stack-for-mcp-env',
          parameters: [
            [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME],
            [$class: 'StringParameterValue', name: 'OS_AZ', value: OS_AZ],
            [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
            [$class: 'BooleanParameterValue', name: 'DELETE_STACK', value: Boolean.valueOf(DELETE_STACK)],
            [$class: 'StringParameterValue', name: 'COMPUTE_NODES_COUNT', value: COMPUTE_NODES_COUNT],
            [$class: 'StringParameterValue', name: 'MCP_VERSION', value: mcpVersion],
            [$class: 'StringParameterValue', name: 'FLAVOR_PREFIX', value: FLAVOR_PREFIX],
            [$class: 'StringParameterValue', name: 'OPENSTACK_ENVIRONMENT', value: OPENSTACK_ENVIRONMENT],
            [$class: 'BooleanParameterValue', name: 'STACK_FULL', value: STACK_FULL.toBoolean()],
            [$class: 'BooleanParameterValue', name: 'COMPUTE_BUNCH', value: COMPUTE_BUNCH.toBoolean()],
            [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
            [$class: 'StringParameterValue', name: 'REFSPEC', value: REFSPEC],
            [$class: 'StringParameterValue', name: 'HEAT_TEMPLATES_REFSPEC', value: HEAT_TEMPLATES_REFSPEC],
            [$class: 'BooleanParameterValue', name: 'MAAS_ENABLE', value: MAAS_ENABLE.toBoolean()],
          ])
  }
  stage ('Provision nodes using MAAS'){
    if ( MAAS_ENABLE.toBoolean() ) {
      def kubernetes = 'no'
      if ( kubernetes_enabled ) { kubernetes = 'yes' }
      sh script: "$WORKSPACE/venv/bin/python2.7 $WORKSPACE/files/generate_snippets.py $STACK_NAME $kubernetes", returnStdout: true
      out = sh script: "$openstack stack show -f value -c outputs $STACK_NAME | jq -r .[0].output_value", returnStdout: true
      cfg01_ip = out.trim()
      ssh_user = 'mcp-scale-jenkins'
      ssh_opt = ' -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null'
      ssh_cmd = "ssh $ssh_opt"
      ssh_cmd_cfg01 = "$ssh_cmd $ssh_user@$cfg01_ip "
      sshagent (credentials: ['mcp-scale-jenkins']) {
        sh "scp $ssh_opt /tmp/machines_template.yml.src $ssh_user@$cfg01_ip:machines_template.yml.src"
        sh "scp $ssh_opt /tmp/dhcp_snippets.yml.src $ssh_user@$cfg01_ip:dhcp_snippets.yml.src"
        sh "$ssh_cmd_cfg01 sudo cp dhcp_snippets.yml.src /srv/salt/reclass/classes/cluster/$STACK_NAME/infra/scale-ci-patch/dhcp_snippets.yml"
        sh "$ssh_cmd_cfg01 sudo cp machines_template.yml.src /srv/salt/reclass/classes/cluster/$STACK_NAME/infra/scale-ci-patch/machines_template.yml"

        //Fix for the https://mirantis.jira.com/browse/PROD-19174
        //sh "$ssh_cmd_cfg01 wget https://raw.githubusercontent.com/salt-formulas/salt-formula-maas/master/_modules/maas.py"
        //sh "$ssh_cmd_cfg01 sudo cp maas.py /srv/salt/env/prd/_modules/maas.py"

        //Apply several fixes for MAAS and provision compute hosts
        sh "scp $ssh_opt $WORKSPACE/files/fixes-for-maas.sh $ssh_user@$cfg01_ip:fixes-for-maas.sh"
        sh "$ssh_cmd_cfg01 sudo bash +x fixes-for-maas.sh"

        //Execute send.event from k8s compute hosts for autoregistrarion
        if ( kubernetes_enabled ){
          sh "$ssh_cmd_cfg01 sudo salt \\\"cmp*\\\" cmd.run \\\"dhclient one1\\\" "
          sh "$ssh_cmd_cfg01 sudo salt \\\"ctl*\\\" cmd.run \\\"dhclient one1\\\" "
          //Workaround for https://mirantis.jira.com/browse/PROD-20216
          sh "$ssh_cmd_cfg01 sudo salt \\\"cmp*\\\" cmd.run \\\"swapoff -a\\\" "
          sh "$ssh_cmd_cfg01 sudo salt \\\"ctl*\\\" cmd.run \\\"swapoff -a\\\" "
          //Workaround for https://mirantis.jira.com/browse/PROD-20185
          sh "scp $ssh_opt $WORKSPACE/files/compute_autoregistration.sh $ssh_user@$cfg01_ip:compute_autoregistration.sh"
          sh "$ssh_cmd_cfg01 sudo salt-cp \\\"cmp*\\\" compute_autoregistration.sh /tmp/autoreg.sh"
          sh "$ssh_cmd_cfg01 sudo salt \\\"cmp*\\\" cmd.run \\\"bash +x /tmp/autoreg.sh $STACK_NAME\\\" "
        }
      }
    }
    else {
      println 'MAAS provisioning is not enabled. Skipping..'
    }
  }
  stage ('Deploy open stack'){
    job_failed = false
    deploy_settings = ''
    if (openstack_enabled){
      deploy_settings = deploy_settings + ' openstack'
    }
    if (kubernetes_enabled){
      deploy_settings = deploy_settings + ' kubernetes'
    }
    if (opencontrail_enabled){
      deploy_settings = deploy_settings + ' opencontrail'
    } else {
      if (openstack_enabled){
        deploy_settings = deploy_settings + ' ovs'
      }
    }
    if (stacklight_enabled){
      deploy_settings = deploy_settings + ' stacklight'
    }
    try {
      build(job: 'run-deploy-job-on-cfg01',
            parameters: [
              [$class: 'StringParameterValue', name: 'REFSPEC', value: REFSPEC],
              [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
              [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME],
              [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
              [$class: 'StringParameterValue', name: 'OPENSTACK_ENVIRONMENT', value: OPENSTACK_ENVIRONMENT]
            ])
    } catch (Exception e) {
      job_failed = true
      job_error = e
    }
    def date = new Date()
    String fDate = date.format( 'yyyy-MM-dd' )
    junit_report = "<?xml version='1.0' encoding='utf-8'?>"
    if (job_failed){
      junit_report = junit_report +
                     "<testsuites><testsuite errors='0' failures='1' tests='1' time='1' >" +
                     "<testcase classname='ScaleDeployment' name='Cluster deploy with$deploy_settings' time='1'> " +
                     '<failure>Deploy failured</failure>'
      } else {
      junit_report = junit_report +
                     "<testsuites><testsuite errors='0' failures='0' tests='1' time='1'>" +
                     "<testcase classname='ScaleDeployment' name='Cluster deploy with$deploy_settings' time='1'> "
      }
    junit_report = junit_report + '</testcase></testsuite></testsuites>'
    writeFile file: '/tmp/scale_cluster_deploy_junut.xml', text: junit_report
    report_cmd = "$report --testrail-run-update --verbose --testrail-url https://mirantis.testrail.com --testrail-user 'mos-scale-jenkins@mirantis.com' " +
                 " --testrail-password 'Qwerty1234' --testrail-project 'Mirantis Cloud Platform' --testrail-milestone 'MCP1.1' " +
                 "--testrail-suite '[MCP_X] integration cases' --testrail-plan-name '[MCP-Q1]System-$fDate' --env 'Dev cloud' " +
                 "--xunit-name-template '{methodname}' --testrail-name-template '{title}' " +
                 '/tmp/scale_cluster_deploy_junut.xml'
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
  if (OPENSTACK_ENVIRONMENT == 'devcloud') {
    stage('Run rally tests'){
      if (runTests) {
        if ( kubernetes_enabled ) {
          println 'Kubernetes testing will be added soon'
        }
        else {
          build(job: 'run-tests-mcp-env',
            parameters: [
              [$class: 'StringParameterValue', name: 'REFSPEC', value: REFSPEC],
              [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME],
              [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
              [$class: 'StringParameterValue', name: 'TEST_IMAGE', value: 'sergeygals/rally'],
              [$class: 'StringParameterValue', name: 'RALLY_CONFIG_REPO', value: 'https://github.com/Mirantis/scale-scenarios'],
              [$class: 'StringParameterValue', name: 'RALLY_CONFIG_BRANCH', value: 'master'],
              [$class: 'StringParameterValue', name: 'RALLY_SCENARIOS', value: 'rally-scenarios-light'],
              [$class: 'StringParameterValue', name: 'RALLY_TASK_ARGS_FILE', value: 'job-params-light.yaml'],
              [$class: 'BooleanParameterValue', name: 'RALLY_SCENARIOS_RECURSIVE', value: true],
              [$class: 'BooleanParameterValue', name: 'REPORT_RALLY_RESULTS_TO_TESTRAIL', value: Boolean.valueOf(REPORT_RALLY_RESULTS_TO_TESTRAIL)],
              [$class: 'BooleanParameterValue', name: 'REPORT_RALLY_RESULTS_TO_SCALE', value: Boolean.valueOf(REPORT_RALLY_RESULTS_TO_SCALE)],
            ]
          )
        }
      }
    }
  }
}
