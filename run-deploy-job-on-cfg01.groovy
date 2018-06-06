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
    stage('Deploy netdata'){
      if (params.DEPLOY_NETDATA) {
        ssh_opt = ' -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null'
        ssh_cmd = "ssh $ssh_opt"
        netdata_repo = 'https://raw.githubusercontent.com/firehol/binary-packages/master'
        netdata_latest = "${netdata_repo}/netdata-latest.gz.run"
        ansiColor('xterm'){
          sshagent (credentials: ['heat-key']) {
            sh "$ssh_cmd ubuntu@$cfg01_ip sudo bash -c \\\"wget -c ${netdata_repo}/\$(curl ${netdata_latest}) -O netdata.run\\\""
            sh "$ssh_cmd ubuntu@$cfg01_ip sudo salt-cp \\\'*\\\' ./netdata.run /root/"
            sh "$ssh_cmd ubuntu@$cfg01_ip sudo salt \\\'*\\\' cmd.run \\\"sh /root/netdata.run --quiet --accept\\\""
            writeFile file: '/tmp/netdata.conf', text: '[global]\n  update every = 5\n  history = 7200\n'
            sh "scp $ssh_opt /tmp/netdata.conf ubuntu@$cfg01_ip:/tmp/netdata.conf"
            sh 'rm -f /tmp/netdata.conf'
            sh "$ssh_cmd ubuntu@$cfg01_ip sudo salt-cp \\\'*\\\' /tmp/netdata.conf /opt/netdata/etc/netdata/netdata.conf"
            sh "$ssh_cmd ubuntu@$cfg01_ip rm -f /tmp/netdata.conf"
            sh "$ssh_cmd ubuntu@$cfg01_ip sudo salt \\\'*\\\' cmd.run \\\"systemctl restart netdata\\\""
          }
        }
      }
    }
    stage ('Execute job on cfg01 node'){
      JSON = '{\"parameter\": [' +
      '{\"name\":\"SALT_MASTER_CREDENTIALS\", \"value\":\"salt\"},' +
      '{\"name\":\"SALT_MASTER_URL\", \"value\":\"http://cfg01:6969\"},' +
      "{\"name\":\"STACK_INSTALL\",\"value\":\"${STACK_INSTALL}\"}," +
      '{\"name\":\"STACK_TYPE\", \"value\":\"physical\"},' +
      '{\"name\":\"ASK_ON_ERROR\", \"value\":\"false\"}' +
      ']}'
      build(job: 'run-job-on-cfg01-jenkins',
        parameters: [
          [$class: 'StringParameterValue', name: 'REFSPEC', value: REFSPEC],
          [$class: 'StringParameterValue', name: 'JOB_NAME', value: 'deploy_openstack'],
          [$class: 'StringParameterValue', name: 'JOB_JSON', value: JSON],
          [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OS_PROJECT_NAME],
          [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
          [$class: 'StringParameterValue', name: 'OPENSTACK_ENVIRONMENT', value: OPENSTACK_ENVIRONMENT]
          ])
    }
}
