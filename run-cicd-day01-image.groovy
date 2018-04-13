node ('python') {
    currentBuild.description = STACK_NAME
      // Configure OpenStack credentials and command
      withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'devcloud-mcp-scale',
          usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD']]) {
              env.OS_USERNAME = OS_USERNAME
              env.OS_PASSWORD = OS_PASSWORD
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
      stage('Deploy netdata'){
        if (params.DEPLOY_NETDATA) {
          ssh_opt = " -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
          ssh_cmd = "ssh $ssh_opt"
          netdata_repo = 'https://raw.githubusercontent.com/firehol/binary-packages/master'
          netdata_latest = "${netdata_repo}/netdata-latest.gz.run"
          ansiColor('xterm'){
            sshagent (credentials: ['heat-key']) {
              sh "$ssh_cmd ubuntu@$cfg01_ip sudo bash -c \\\"wget -c ${netdata_repo}/\$(curl ${netdata_latest}) -O netdata.run\\\""
              sh "$ssh_cmd ubuntu@$cfg01_ip sudo salt-cp \\\'*\\\' ./netdata.run /root/"
              sh "$ssh_cmd ubuntu@$cfg01_ip sudo salt \\\'*\\\' cmd.run \\\"sh /root/netdata.run --quiet --accept\\\""
              writeFile file: "/tmp/netdata.conf", text: "[global]\n  update every = 5\n  history = 7200\n"
              sh "scp $ssh_opt /tmp/netdata.conf ubuntu@$cfg01_ip:/tmp/netdata.conf"
              sh "rm -f /tmp/netdata.conf"
              sh "$ssh_cmd ubuntu@$cfg01_ip sudo salt-cp \\\'*\\\' /tmp/netdata.conf /opt/netdata/etc/netdata/netdata.conf"
              sh "$ssh_cmd ubuntu@$cfg01_ip rm -f /tmp/netdata.conf"
              sh "$ssh_cmd ubuntu@$cfg01_ip sudo salt \\\'*\\\' cmd.run \\\"systemctl restart netdata\\\""
            }
          }
        }
      }
      stage ('Execute job on cfg01 node'){
        JSON = "{\"parameter\": ["+
        "{\"name\":\"SALT_MASTER_CREDENTIALS\", \"value\":\"deploy\"},"+
        "{\"name\":\"SALT_MASTER_URL\", \"value\":\"http://cfg01:6969\"},"+
        "{\"name\":\"STACK_INSTALL\",\"value\":\"${STACK_INSTALL}\"},"+
        "{\"name\":\"STACK_TYPE\", \"value\":\"physical\"},"+
        "{\"name\":\"ASK_ON_ERROR\", \"value\":\"false\"}"+
        "]}"
        build(job: 'run-job-on-cfg01-jenkins',
          parameters: [
            [$class: 'StringParameterValue', name: 'JOB_NAME', value: 'deploy_openstack'],
            [$class: 'StringParameterValue', name: 'JOB_JSON', value: JSON],
            [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
            ])
      }
}
