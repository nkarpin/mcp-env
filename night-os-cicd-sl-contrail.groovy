node ('python'){
  stage('Run nightly tests'){
    build(
          job: 'create-mcp-env',
          parameters: [
              string(name: 'STACK_NAME', value: 'night-os-cicd-sl-oc'),
              string(name: 'OS_PROJECT_NAME', value: 'mcp-scale-dev'),
              string(name: 'STACK_INSTALL', value: 'core,cicd,openstack,stacklight,opencontrail'),
          ],
          propagate: true,
          wait: true,
      )
  }
}
