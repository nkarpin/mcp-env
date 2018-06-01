ssh_opt = " -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
jenkins_user = "admin"
jenkins_pass = "r00tme"
ssh_user = "mcp-scale-jenkins"
def remote_ssh_cmd(server, command){
  writeFile file: "/tmp/cmd-tmp.sh", text: command
  sh "scp $ssh_opt /tmp/cmd-tmp.sh $ssh_user@$server:/tmp/cmd-tmp.sh"
  sh "ssh $ssh_opt $ssh_user@$server bash -xe /tmp/cmd-tmp.sh"
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
  }
  openstack = "set +x; venv/bin/openstack "
  vpython = "venv/bin/python"
  report = "venv/bin/report"
  jenkins_user = "admin"
  jenkins_pass = "r00tme"
  stage ('Build venv'){
      sh "virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient 'jenkins-job-builder>=2.0.0.0b2' junitparser git+https://github.com/dis-xcom/testrail_reporter"
  }
  stage ('Get cfg01 floating'){
    out = sh script: "$openstack stack show -f value -c outputs $STACK_NAME | jq -r .[0].output_value", returnStdout: true
    cfg01_ip = out.trim()
  }
  stage ('Install docker on rally node'){
    ssh_cmd = "ssh $ssh_opt"
    ssh_cmd_cfg01 = "$ssh_cmd $ssh_user@$cfg01_ip "
    rally_node = "cfg01.${STACK_NAME}.local"
    sshagent (credentials: ['mcp-scale-jenkins']) {
      sh "$ssh_cmd_cfg01 sudo salt $rally_node pkg.install pkgs=[\\\'apt-transport-https\\\',\\\'ca-certificates\\\',\\\'curl\\\',\\\'build-essential\\\',\\\'python-dev\\\',\\\'gcc\\\']"
      writeFile file: "/tmp/cmd.sh", text: 'curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -\nadd-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"'
      sh "scp $ssh_opt /tmp/cmd.sh $ssh_user@$cfg01_ip:/tmp/cmd.sh"
      sh "$ssh_cmd_cfg01 sudo salt-cp $rally_node /tmp/cmd.sh /tmp/tmp-cmd.sh"
      sh "$ssh_cmd_cfg01 sudo salt $rally_node cmd.run \\\'bash /tmp/tmp-cmd.sh\\\'"
      sh "$ssh_cmd_cfg01 sudo mkdir -p /etc/docker/"
      writeFile file: "/tmp/docker-host.yml", text: '{"bip": "10.99.0.1/16", "fixed-cidr": "10.99.0.1/17"}'
      sh "scp $ssh_opt /tmp/docker-host.yml $ssh_user@$cfg01_ip:/tmp/docker-host.yml"
      sh "$ssh_cmd_cfg01 sudo mv /tmp/docker-host.yml /etc/docker/daemon.json"
      sh "$ssh_cmd_cfg01 sudo salt $rally_node pkg.install docker-ce refresh=true"
    }
  }
  stage ('Deploy run-rally-cfg01 to cfg01'){
    sh "cd files; PATH=${PATH}:${WORKSPACE}/venv/bin ./deploy-run-rally-cfg01-job.sh run-rally-cfg01 $cfg01_ip"
  }
  stage ('Prepare OpenStack cluster for rally tests'){
    sshagent (credentials: ['mcp-scale-jenkins']) {
      sh "$ssh_cmd_cfg01 virtualenv /home/$ssh_user/venv"
      remote_ssh_cmd(cfg01_ip, "sudo salt --out=newline_values_only --out-file=./openrc 'ctl01*' cmd.run 'cat /root/keystonercv3'")
      sh "$ssh_cmd_cfg01 /home/$ssh_user/venv/bin/pip install python-openstackclient"
      openstack_cfg01 = "set +x; . /home/$ssh_user/openrc; /home/$ssh_user/venv/bin/openstack --insecure "
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor delete m1.small || true")
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor delete m1.nano || true")
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor delete m1.tiny || true")
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor create --disk 1 --vcpus 1 --ram 512 m1.small")
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor create --disk 1 --vcpus 1 --ram 64 m1.nano")
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor create --disk 1 --vcpus 1 --ram 128 m1.tiny")
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 image  delete TestVM || true")
      remote_ssh_cmd(cfg01_ip, "wget --progress=dot:mega -c http://download.cirros-cloud.net/0.3.5/cirros-0.3.5-x86_64-disk.img -O /home/$ssh_user/cirros-0.3.5-x86_64-disk.img")
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 image create --force --public --file /home/$ssh_user/cirros-0.3.5-x86_64-disk.img --disk-format qcow2 TestVM")
      remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 volume type create standard-iops || true")
    }
  }
}
