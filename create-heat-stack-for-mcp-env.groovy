String CREATE_COMPLETE = 'CREATE_COMPLETE'
String CREATE_IN_PROGRESS = 'CREATE_IN_PROGRESS'
String True = 'True'
String False = 'False'
String STACK_INSTALL = 'STACK_INSTALL'

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
    stage ('Checkout'){
      repo_url = 'ssh://mcp-jenkins@gerrit.mcp.mirantis.net:29418/mcp-env/heat-templates'
      checkout([
        $class: 'GitSCM',
        branches: [
              [name: 'FETCH_HEAD'],
          ],
        userRemoteConfigs: [
              [url: repo_url, refspec: env.HEAT_TEMPLATES_REFSPEC ?: 'master', credentialsId: 'gerrit'],
          ],
      ])
    }
    stage ('Build venv'){
        sh 'virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient'
    }
    stage ('Deploy heat stack'){
        if (params.DELETE_STACK){
            build(job: 'delete-heat-stack-for-mcp-env',
              parameters: [
                string( name: 'REFSPEC', value: REFSPEC),
                string( name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME),
                string( name: 'STACK_NAME', value: STACK_NAME),
              ])
        }
        if (params.HEAT_ENV_FILE != ''){
          def common = new com.mirantis.mk.Common()
          def network01_dhcp = True
          def install_openstack = False
          def install_k8s = False
          def install_cicd = False
          def install_contrail = False
          def install_stacklight = False
          def install_maas = False
          def install_ceph = False
          def install_tenant_telemetry = False
          if (common.validInputParam('TENANT_TELEMETRY_ENABLE')){
            install_tenant_telemetry = TENANT_TELEMETRY_ENABLE.toBoolean()
          }
          def nameservers = '8.8.8.8'
          if (OPENSTACK_ENVIRONMENT == 'presales') {
            nameservers = '10.10.0.15'
          } else if (OPENSTACK_ENVIRONMENT == 'devcloud') {
            nameservers = '172.18.176.6'
          }
          if ( STACK_FULL.toBoolean() ) { network01_dhcp = False }
          if ( MAAS_ENABLE.toBoolean() ) { install_maas = True }
          if ( OFFLINE_DEPLOYMENT.toBoolean() ) {
            security_group = 'security_group_offline'
          } else {
            security_group = 'security_group_online'
          }
          if (common.checkContains(STACK_INSTALL, 'openstack')) { install_openstack = True }
          if (common.checkContains(STACK_INSTALL, 'k8s')) { install_k8s = True }
          if (common.checkContains(STACK_INSTALL, 'cicd')) { install_cicd = True }
          if (common.checkContains(STACK_INSTALL, 'contrail')) { install_contrail = True }
          if (common.checkContains(STACK_INSTALL, 'stacklight')) { install_stacklight = True }
          if (common.checkContains(STACK_INSTALL, 'ceph')) { install_ceph = True }

          cmd = "$heat create -e env/$HEAT_ENV_FILE " +
            "--parameter osd_node_count=${OSD_NODES_COUNT} " +
            "--parameter cluster_node_count=${COMPUTE_NODES_COUNT} " +
            "--parameter cluster_nameservers=${nameservers} " +
            "--parameter flavor_prefix=${FLAVOR_PREFIX} " +
            "--parameter cluster_zone=${OS_AZ} " +
            "--parameter mcp_version=${MCP_VERSION} " +
            "--parameter network01_dhcp=${network01_dhcp} " +
            "--parameter install_openstack=${install_openstack} " +
            "--parameter install_k8s=${install_k8s} " +
            "--parameter install_cicd=${install_cicd} " +
            "--parameter install_stacklight=${install_stacklight} " +
            "--parameter install_ceph=${install_ceph} " +
            "--parameter install_contrail=${install_contrail} " +
            "--parameter stack_full=${STACK_FULL} " +
            "--parameter compute_bunch=${COMPUTE_BUNCH} " +
            "--parameter install_maas=${install_maas} " +
            "--parameter install_tenant_telemetry=${install_tenant_telemetry} " +
            "--parameter opencontrail_version=${OPENCONTRAIL_VERSION} " +
            "--parameter security_group=${security_group} " +
            "-t template/$HEAT_TEMPLATE_FILE $STACK_NAME"
          print ('Try to start ' + cmd)
          sh cmd
        } else {
          sh "$heat create -t template/$HEAT_TEMPLATE_FILE $STACK_NAME"
        }
    }
    stage ('Wait heat stack'){
        timeout(time: 100, unit: 'MINUTES'){
        out = ''
            while (out.trim() != CREATE_COMPLETE){
                out = sh script: "$heat list | awk -v stack=$STACK_NAME '{if (\$4==stack) print \$6}'", returnStdout: true
                print ("The stack status is $out")
                if (out.trim() != CREATE_IN_PROGRESS && out.trim() != CREATE_COMPLETE) {
                    error("Something wrong with stack! The status is $out")
                }
            }
        }
    }
}
