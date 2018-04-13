ssh_opt = " -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
jenkins_user = "admin"
jenkins_pass = "r00tme"
def remote_ssh_cmd(server, command){
  writeFile file: "/tmp/cmd-tmp.sh", text: command
  sh "scp $ssh_opt /tmp/cmd-tmp.sh ubuntu@$server:/tmp/cmd-tmp.sh"
  sh "ssh $ssh_opt ubuntu@$server bash -xe /tmp/cmd-tmp.sh"
}
node ('master') {
  timestamps(){
    repo_url = "ssh://mos-scale-jenkins@gerrit.mirantis.com:29418/mos-scale/mos-scale-infra"
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
    vpython = "venv/bin/python"
    report = "venv/bin/report"
    jenkins_user = "admin"
    jenkins_pass = "r00tme"
    stage ('Checkout'){
      git url: repo_url, branch: 'master', credentialsId: 'ece6c586-cd26-481c-92a8-ccae6bf9cf86'
    }
    stage ('Fetch review'){
      if (params.REFSPEC != ""){
        // sh "git clean -xfd"
        sshagent (credentials: ['ece6c586-cd26-481c-92a8-ccae6bf9cf86']) {
          sh "git fetch $repo_url $REFSPEC"
          sh "git checkout FETCH_HEAD"
        }
      }
    }
    stage ('Build venv'){
        sh "virtualenv venv; venv/bin/pip install python-openstackclient 'jenkins-job-builder>=2.0.0.0b2' junitparser git+https://github.com/dis-xcom/testrail_reporter"
    }
    stage ('Get cfg01 floating'){
      out = sh script: "$openstack server list | grep cfg01.$STACK_NAME | grep -oP 'net01=.*, .*(;| )' | awk '{print \$2}' | tr -d ';'", returnStdout: true
      cfg01_ip = out.trim()
    }
    stage ('Install docker on rally node'){
      ssh_cmd = "ssh $ssh_opt"
      ssh_cmd_cfg01 = "$ssh_cmd ubuntu@$cfg01_ip "
      rally_node = "cfg01.${STACK_NAME}.local"
      sshagent (credentials: ['heat-key']) {
        sh "$ssh_cmd_cfg01 sudo salt $rally_node pkg.install pkgs=[\\\'apt-transport-https\\\', \\\'ca-certificates\\\', \\\'curl\\\', \\\'software-properties-common\\\']"
        writeFile file: "/tmp/cmd.sh", text: 'curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -\nadd-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"'
        sh "scp $ssh_opt /tmp/cmd.sh ubuntu@$cfg01_ip:/tmp/cmd.sh"
        sh "$ssh_cmd_cfg01 sudo salt-cp $rally_node /tmp/cmd.sh /tmp/tmp-cmd.sh"
        sh "$ssh_cmd_cfg01 sudo salt $rally_node cmd.run \\\'bash /tmp/tmp-cmd.sh\\\'"
        sh "$ssh_cmd_cfg01 sudo mkdir -p /etc/docker/"
        writeFile file: "/tmp/docker-host.yml", text: '{"bip": "10.99.0.1/16", "fixed-cidr": "10.99.0.1/17"}'
        sh "scp $ssh_opt /tmp/docker-host.yml ubuntu@$cfg01_ip:/tmp/docker-host.yml"
        sh "$ssh_cmd_cfg01 sudo mv /tmp/docker-host.yml /etc/docker/daemon.json"
        sh "$ssh_cmd_cfg01 sudo salt $rally_node pkg.install docker-ce refresh=true"
      }
    }
    stage ('Deploy run_rally_cfg01 to cfg01'){
      sh "cd jenkins_job_builder; PATH=${PATH}:${WORKSPACE}/venv/bin ./deploy_cfg01.sh run_rally_cfg01 $cfg01_ip"
    }
    stage ('Prepare OpenStack cluster for rally tests'){
      sshagent (credentials: ['heat-key']) {
        sh "$ssh_cmd_cfg01 virtualenv /home/ubuntu/venv"
        sh "scp $ssh_opt openrc/mcp-test-cloud-1 ubuntu@$cfg01_ip:/home/ubuntu/openrc"
        remote_ssh_cmd(cfg01_ip, "sed -i '/OS_PASSWORD/d' /home/ubuntu/openrc")
        remote_ssh_cmd(cfg01_ip, "sudo grep keystone_admin_password_generated /srv/salt/reclass/classes/cluster/$STACK_NAME/infra/secrets.yml | awk '{print \$2}' > /tmp/os_pass")
        remote_ssh_cmd(cfg01_ip, "echo \"export OS_PASSWORD=\$(cat /tmp/os_pass)\" >> /home/ubuntu/openrc")
        sh "$ssh_cmd_cfg01 /home/ubuntu/venv/bin/pip install python-openstackclient"
        openstack_cfg01 = 'set +x; . /home/ubuntu/openrc; /home/ubuntu/venv/bin/openstack --insecure '
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor delete m1.small || true")
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor delete m1.nano || true")
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor delete m1.tiny || true")
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor create --disk 1 --vcpus 1 --ram 512 m1.small")
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor create --disk 1 --vcpus 1 --ram 246 m1.nano")
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 flavor create --disk 1 --vcpus 1 --ram 128 m1.tiny")
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 image  delete TestVM || true")
        remote_ssh_cmd(cfg01_ip, "wget --progress=dot:mega -c http://download.cirros-cloud.net/0.3.5/cirros-0.3.5-x86_64-disk.img -O /home/ubuntu/cirros-0.3.5-x86_64-disk.img")
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 image create --force --public --file /home/ubuntu/cirros-0.3.5-x86_64-disk.img --disk-format qcow2 TestVM")
        remote_ssh_cmd(cfg01_ip, "$openstack_cfg01 volume type create standard-iops || true")
      }
    }
  
  }
}
