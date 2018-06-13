String project_name = 'mcp-scale'

node ('python'){
  stage('Run nightly tests'){
    build(
          job: 'create-mcp-env',
          parameters: [
              string(name: 'STACK_NAME', value: 'night-os-cicd-sl-ovs'),
              string(name: 'OS_PROJECT_NAME', value: project_name),
              string(name: 'OS_AZ', value: project_name),
              string(name: 'STACK_INSTALL', value: 'core,cicd,openstack,stacklight,ovs'),
              booleanParam(name: 'DELETE_STACK', value: true),
          ],
          propagate: true,
          wait: true,
      )
  }
}
