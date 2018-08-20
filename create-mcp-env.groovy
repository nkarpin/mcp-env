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
 *   OPENCONTRAIL_VERSION                   Version of opencontrail will be deployed
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
 *   SKIP_LIST                              List of the scenarios which should be skipped.
 *
 **/

def common = new com.mirantis.mk.Common()

def runTests = true
if (common.validInputParam('RUN_TESTS')){
  runTests = RUN_TESTS.toBoolean()
}
String True = 'True'
String False = 'False'
String default_context = 'default_context'
String domain_suf = '.local'
String openstack_context = 'openstack_enabled'
String kubernetes_context = 'kubernetes_enabled'
String stacklight_context = 'stacklight_enabled'
String cicd_context = 'cicd_enabled'
String opencontrail_context = 'opencontrail_enabled'
String ceph_context = 'ceph_enabled'
String platform = 'platform'
String public_host = 'public_host'
String file_suf = '.yaml'
String default_version = 'testing'
String xml_path = '/tmp/scale_cluster_deploy_junut.xml'
String split_char = ','
String ssh_user = 'mcp-scale-jenkins'
String ssh_opt = ' -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null'
String ssh_cmd = "ssh $ssh_opt"
String apt_server = '10.10.0.14'
String cfgBootstrapDriveUrl = ''


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
    tmp_template_file = WORKSPACE + '/' + JOB_NAME + file_suf
    sh 'rm -f ' + tmp_template_file
    templateContext[default_context]['cluster_domain'] = STACK_NAME + domain_suf
    templateContext[default_context]['cluster_name'] = STACK_NAME
    templateContext[default_context]['openldap_domain'] = STACK_NAME + domain_suf
    templateContext[default_context]['openstack_compute_count'] = COMPUTE_NODES_COUNT
    offline_deployment = 'offline_deployment'
    if (OFFLINE_DEPLOYMENT.toBoolean()) {
      templateContext[default_context][offline_deployment] = True
      templateContext[default_context]['local_repositories'] = True
      templateContext[default_context]['aptly_server_hostname'] = 'apt'
      templateContext[default_context]['aptly_server_deploy_address'] = apt_server
      templateContext[default_context]['aptly_server_http_static_http_port'] = '8078'
      templateContext[default_context]['aptly_server_control_address'] = apt_server
      templateContext[default_context]['local_repo_url'] = apt_server
    } else {
      templateContext[default_context][offline_deployment] = False
    }
    templateContext[default_context][public_host] = ''
    def stack_install_options = STACK_INSTALL.split(split_char)
    openstack_enabled = false
    templateContext[default_context][openstack_context] = False
    kubernetes_enabled = false
    templateContext[default_context][kubernetes_context] = False
    opencontrail_enabled = false
    templateContext[default_context][opencontrail_context] = False
    ceph_enabled = false
    templateContext[default_context][ceph_context] = False
    stacklight_enabled = false
    templateContext[default_context][stacklight_context] = False
    cicd_enabled = false
    templateContext[default_context][cicd_context] = False
    stack_install_options.each {
      switch ( it ) {
        case 'openstack':
          openstack_enabled = true
          templateContext[default_context][openstack_context] = True
          templateContext[default_context][platform] = openstack_context
          templateContext[default_context][public_host] = "\${_param:openstack_proxy_address}"
          break
        case 'k8s':
          kubernetes_enabled = true
          templateContext[default_context][kubernetes_context] = True
          templateContext[default_context][platform] = kubernetes_context
          templateContext[default_context][public_host] = "\${_param:infra_config_address}"
          break
        case 'stacklight':
          stacklight_enabled = true
          templateContext[default_context][stacklight_context] = True
          break
        case 'cicd':
          cicd_enabled = true
          templateContext[default_context][cicd_context] = True
          break
        case 'opencontrail':
          opencontrail_enabled = true
          templateContext[default_context][opencontrail_context] = True
          templateContext[default_context]['openstack_network_engine'] = 'opencontrail'
          templateContext[default_context]['opencontrail_version'] = OPENCONTRAIL_VERSION
          break
        case 'ceph':
          ceph_enabled = true
          templateContext[default_context][ceph_context] = True
          templateContext[default_context]['ceph_osd_count'] = OSD_NODES_COUNT
          templateContext[default_context]['ceph_osd_node_count'] = OSD_NODES_COUNT
          break
      }
    }
    if ( MAAS_ENABLE.toBoolean() && kubernetes_enabled ) { templateContext[default_context]['kubernetes_keepalived_vip_interface'] = 'one1' }
    writeYaml file: tmp_template_file, data: templateContext
    COOKIECUTTER_TEMPLATE_CONTEXT = readFile tmp_template_file
    archiveArtifacts artifacts: JOB_NAME + file_suf
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
    git_path = "/tmp/cfg01.${STACK_NAME}-config/model/model"
    sh "rm -v cfg01.${STACK_NAME}-config.iso"
    common.infoMsg('Backupping origin structure')
    sh(script: "mkdir -vp /tmp/cfg01.${STACK_NAME}-config/original_data/")
    sh(script: "rsync -aRr --exclude=original_data /tmp/cfg01.${STACK_NAME}-config/ /tmp/cfg01.${STACK_NAME}-config/original_data/ || true")
    sh "rm -v /tmp/cfg01.${STACK_NAME}-config/meta-data"
    // Calculation openssh_groups variable for old releases ( older then https://gerrit.mcp.mirantis.net/#/c/19109/)
    opensshGroups = templateContext[default_context]['openssh_groups'].tokenize(split_char)
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
    // Clone mcp-scale-jenkins user from master and add it to infra for old releases (older then https://gerrit.mcp.mirantis.net/#/c/19499/)
    neededUser = 'system.openssh.server.team.members.mcp-scale-jenkins'
    systemLevelPath = "/tmp/cfg01.${STACK_NAME}-config/model/model/classes/system"
    QaScaleFile = systemLevelPath + '/openssh/server/team/qa_scale.yml'
    UserExists = sh script: "grep $neededUser $QaScaleFile", returnStatus: true
    if (UserExists == 0){
      println "User $neededUser found in system/openssh"
    } else {
      println "User $neededUser not found in system/openssh. Adding it to infra/init.yml"
      masterMcpScaleJenkinsUrl = "'https://gerrit.mcp.mirantis.net/gitweb?p=salt-models/reclass-system.git;a=blob_plain;f=openssh/server/team/members/mcp-scale-jenkins.yml;hb=refs/heads/master'"
      McpScaleFile = model_path + '/infra/mcp-scale-jenkins.yml'
      sh "curl -s ${masterMcpScaleJenkinsUrl} > ${McpScaleFile}"
      sh "$reclass_tools add-key --merge classes cluster.${STACK_NAME}.infra.mcp-scale-jenkins $infraInitFile"
    }
    // Add Validate job from master to cluster level
    sh "curl -s 'https://gerrit.mcp.mirantis.net/gitweb?p=salt-models/reclass-system.git;a=blob_plain;f=jenkins/client/job/validate.yml;hb=refs/heads/master' > $model_path/infra/validate.yml"
    sh "$reclass_tools add-key --merge classes cluster.${STACK_NAME}.infra.validate $model_path/infra/config.yml"
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
        if ( !ceph_enabled ){
          sh "mkdir $model_path/openstack/scale-ci-patch"
          sh "$reclass_tools add-key --merge classes cluster.${STACK_NAME}.openstack.scale-ci-patch.compute $model_path/openstack/compute/init.yml"
          sh "$reclass_tools add-key --merge classes system.cinder.volume.single $model_path/openstack/compute/init.yml"
          sh "$reclass_tools add-key --merge classes system.cinder.volume.notification.messagingv2 $model_path/openstack/compute/init.yml"
          sh "sed -i '/system.cinder.volume.single/d' $model_path/openstack/control.yml"
          sh "sed -i '/system.cinder.volume.notification.messagingv2/d' $model_path/openstack/control.yml"
          sh "cp -f $source_patch_path/openstack-compute.yml.src $model_path/openstack/scale-ci-patch/compute.yml"
        }
        if (!opencontrail_enabled || !MAAS_ENABLE.toBoolean()) {
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
        if (templateContext[default_context]['k8s_keepalived_vip_vrid']) {
          def k8s_keepalived_vip_vrid = templateContext[default_context]['k8s_keepalived_vip_vrid']
          sh "$reclass_tools add-key parameters._param.keepalived_vip_virtual_router_id $k8s_keepalived_vip_vrid $model_path/kubernetes/control.yml"
        }
        // insecure API binding
        if (templateContext[default_context]['k8s_api_insecure_bind'] == True) {
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
        sh "sed -i 's/opencontrail_compute_iface_mask: .*/opencontrail_compute_iface_mask: 16/' $model_path/opencontrail/init.yml"
        if ( OPENCONTRAIL_VERSION == '4.0' ) {
          sh "cp -f $source_patch_path/opencontrail-oc4-virtual.yml.src $model_path/opencontrail/networking/virtual.yml"
          sh "sed -i 's/keepalived_vip_interface: eth1/keepalived_vip_interface: ens3/' $model_path/opencontrail/analytics.yml"
          sh "sed -i 's/keepalived_vip_interface: eth1/keepalived_vip_interface: ens3/' $model_path/opencontrail/control.yml"
        }
        else {
          sh "cp -f $source_patch_path/opencontrail-virtual.yml.src $model_path/opencontrail/networking/virtual.yml"
          sh "sed -i 's/keepalived_vip_interface: eth1/keepalived_vip_interface: eth0/' $model_path/opencontrail/analytics.yml"
          sh "sed -i 's/keepalived_vip_interface: eth1/keepalived_vip_interface: eth0/' $model_path/opencontrail/control.yml"
        }
      }
      // Modify ceph settings
      if ( ceph_enabled ) {
        sh "cp -f $source_patch_path/ceph-osd.yml.src $model_path/ceph/networking/osd.yml"
        sh "cp -f $source_patch_path/ceph-virtual.yml.src $model_path/ceph/networking/virtual.yml"
        sh "sed -i 's/pg_num: 128/pg_num: 4/g' $model_path/ceph/setup.yml"
        sh "sed -i 's/pgp_num: 128/pgp_num: 4/g' $model_path/ceph/setup.yml"
      }
    }
    //Commit all model changes to local git
    sh "git -C $git_path add $model_path/* "
    sh "git -C $git_path commit -a -m 'Add automation changes'"
  }
  stage ('Build config drive image'){
    sh "mkisofs -o ${WORKSPACE}/cfg01.${STACK_NAME}-config.iso -V cidata -r -J --quiet /tmp/cfg01.${STACK_NAME}-config"
  }

  // TODO(vsaienko) remove this after some period to make sure current builds are not affected.
  stage('Delete old image'){
    sh "for i in \$($openstack image list | grep -w cfg01-$STACK_NAME-config |  cut -f 2 -d'|'); do $openstack image delete \$i; done || true"
  }

  stage('Collect artifatcs'){
    archiveArtifacts artifacts: "cfg01.${STACK_NAME}-config.iso"
    cfgBootstrapDriveUrl = "${env.BUILD_URL}/artifact/cfg01.$STACK_NAME-config.iso"
  }
  stage('Update VMs images if needed'){
    mcpVersion = templateContext[default_context]['mcp_version']
    if (mcpVersion == '') {
      mcpVersion = default_version
    }
    vcpImages = ['ubuntu-16-04-x64-mcp',
                 'ubuntu-14-04-x64-mcp',]
    if (OFFLINE_DEPLOYMENT.toBoolean()) {
      vcpImages += 'mcp-offline-image-'
    }
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
        sh "wget --progress=dot:giga -O ./scale-${vcpImage}${mcpVersion}.qcow2 ${vmImageUrl}"
        sh "md5sum ./scale-${vcpImage}${mcpVersion}.qcow2"
        sh "$openstack image delete scale-${vcpImage}${mcpVersion} || true"
        sh "$openstack image create --public --disk-format qcow2 --file ./scale-${vcpImage}${mcpVersion}.qcow2 scale-${vcpImage}${mcpVersion}"
        sh "rm ./scale-${vcpImage}${mcpVersion}.qcow2"
      }
    }
  }
  stage('Update day01 image if needed'){
    mcpVersion = templateContext[default_context]['mcp_version']
    if (mcpVersion == '') {
      mcpVersion = default_version
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
      sh "$openstack image create --public --disk-format qcow2 --file ./scale-cfg01-day01-${mcpVersion}.qcow2 scale-cfg01-day01-${mcpVersion}"
      sh "rm ./scale-cfg01-day01-${mcpVersion}.qcow2"
    }
  }
  stage ('Deploy heat stack'){
    build(job: 'create-heat-stack-for-mcp-env',
          parameters: [
            string( name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME),
            string( name: 'OS_AZ', value: OS_AZ),
            string( name: 'STACK_NAME', value: STACK_NAME),
            booleanParam( name: 'DELETE_STACK', value: Boolean.valueOf(DELETE_STACK)),
            string( name: 'COMPUTE_NODES_COUNT', value: COMPUTE_NODES_COUNT),
            string( name: 'OSD_NODES_COUNT', value: OSD_NODES_COUNT),
            string( name: 'MCP_VERSION', value: mcpVersion),
            string( name: 'FLAVOR_PREFIX', value: FLAVOR_PREFIX),
            string( name: 'OPENSTACK_ENVIRONMENT', value: OPENSTACK_ENVIRONMENT),
            booleanParam( name: 'STACK_FULL', value: STACK_FULL.toBoolean()),
            booleanParam( name: 'COMPUTE_BUNCH', value: COMPUTE_BUNCH.toBoolean()),
            string( name: 'STACK_INSTALL', value: STACK_INSTALL),
            string( name: 'REFSPEC', value: REFSPEC),
            string( name: 'HEAT_TEMPLATES_REFSPEC', value: HEAT_TEMPLATES_REFSPEC),
            booleanParam( name: 'MAAS_ENABLE', value: MAAS_ENABLE.toBoolean()),
            booleanParam( name: 'OFFLINE_DEPLOYMENT', value: OFFLINE_DEPLOYMENT.toBoolean()),
            booleanParam( name: 'TENANT_TELEMETRY_ENABLE', value: templateContext[default_context].get('tenant_telemetry_enabled', False).toBoolean()),
            string( name: 'OPENCONTRAIL_VERSION', value: OPENCONTRAIL_VERSION),
            string( name: 'CFG_BOOTSTRAP_DRIVE_URL', value: cfgBootstrapDriveUrl),
          ])

    out = sh script: "$openstack stack show -f value -c outputs $STACK_NAME | jq -r .[0].output_value", returnStdout: true
    cfg01_ip = out.trim()
    currentBuild.description += " - ${cfg01_ip}"

  }
  stage ('Create loop devices for CEPH'){
    if ( ceph_enabled ){
      ssh_cmd_cfg01 = "$ssh_cmd $ssh_user@$cfg01_ip "
      sshagent (credentials: [ssh_user]) {
        sh "$ssh_cmd_cfg01 sudo salt \\\'osd*\\\' cmd.run \\\'/bin/dd if=/dev/zero of=/disk0 bs=1M count=5024\\\'"
        sh "$ssh_cmd_cfg01 sudo salt \\\'osd*\\\' cmd.run \\\'/bin/dd if=/dev/zero of=/disk1 bs=1M count=5024\\\'"
        sh "$ssh_cmd_cfg01 sudo salt \\\'osd*\\\' cmd.run \\\'/sbin/losetup /dev/loop20 /disk0\\\'"
        sh "$ssh_cmd_cfg01 sudo salt \\\'osd*\\\' cmd.run \\\'/sbin/losetup /dev/loop21 /disk1\\\'"
      }
    }
  }
  stage ('Provision nodes using MAAS'){
    if ( MAAS_ENABLE.toBoolean() ) {
      def kubernetes = 'no'
      if ( kubernetes_enabled ) { kubernetes = 'yes' }
      sh script: "$WORKSPACE/venv/bin/python2.7 $WORKSPACE/files/generate_snippets.py $STACK_NAME $kubernetes", returnStdout: true
      ssh_cmd_cfg01 = "$ssh_cmd $ssh_user@$cfg01_ip "
      sshagent (credentials: [ssh_user]) {
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
        //Commit all MAAS changes to local git
        sh "$ssh_cmd_cfg01 sudo git -C /srv/salt/reclass add /srv/salt/reclass/classes/cluster/$STACK_NAME/*"
        sh "$ssh_cmd_cfg01 sudo git -C /srv/salt/reclass commit -a -m \\\"Add MAAS changes to git\\\""
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
              string( name: 'REFSPEC', value: REFSPEC),
              string( name: 'STACK_NAME', value: STACK_NAME),
              string( name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME),
              string( name: 'STACK_INSTALL', value: STACK_INSTALL),
              string( name: 'OPENSTACK_ENVIRONMENT', value: OPENSTACK_ENVIRONMENT),
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
    writeFile file: xml_path, text: junit_report
    report_cmd = "$report --testrail-run-update --verbose --testrail-url https://mirantis.testrail.com --testrail-user 'mos-scale-jenkins@mirantis.com' " +
                 " --testrail-password 'Qwerty1234' --testrail-project 'Mirantis Cloud Platform' --testrail-milestone 'MCP1.1' " +
                 "--testrail-suite '[MCP_X] integration cases' --testrail-plan-name '[MCP-Q1]System-$fDate' --env 'Dev cloud' " +
                 "--xunit-name-template '{methodname}' --testrail-name-template '{title}' " +
                 xml_path
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
        // TODO: use upstream image everywhere
        def rally_scenario
        def rally_image
        def default_branch = 'master'
        if (openstack_enabled){
          rally_scenario = 'rally-scenarios-light'
          rally_image = 'xrally/xrally-openstack:1.2.0'
        }
        if (kubernetes_enabled){
          rally_scenario = 'rally-k8s'
          rally_image = 'xrally/xrally-openstack'
        }
        build(job: 'run-tests-mcp-env',
          parameters: [
            string( name: 'REFSPEC', value: REFSPEC),
            string( name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME),
            string( name: 'STACK_NAME', value: STACK_NAME),
            string( name: 'TEST_IMAGE', value: rally_image),
            booleanParam( name: 'K8S_RALLY', value: Boolean.valueOf(kubernetes_enabled)),
            string( name: 'SKIP_LIST', value: SKIP_LIST),
            string( name: 'RALLY_PLUGINS_REPO', value: 'https://github.com/Mirantis/rally-plugins'),
            string( name: 'RALLY_PLUGINS_BRANCH', value: default_branch),
            string( name: 'RALLY_CONFIG_REPO', value: 'https://github.com/Mirantis/scale-scenarios'),
            string( name: 'RALLY_CONFIG_BRANCH', value: 'stable'),
            string( name: 'RALLY_SCENARIOS', value: rally_scenario),
            string( name: 'RALLY_TASK_ARGS_FILE', value: 'job-params-light.yaml'),
            booleanParam( name: 'RALLY_SCENARIOS_RECURSIVE', value: true),
            booleanParam( name: 'REPORT_RALLY_RESULTS_TO_TESTRAIL', value: Boolean.valueOf(REPORT_RALLY_RESULTS_TO_TESTRAIL)),
            booleanParam( name: 'REPORT_RALLY_RESULTS_TO_SCALE', value: Boolean.valueOf(REPORT_RALLY_RESULTS_TO_SCALE)),
          ]
        )
      }
    }
  }
}
