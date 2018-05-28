node ('python'){
  stage('Run nightly tests'){
    build(
          job: 'create-mcp-env',
          parameters: [
              string(name: 'STACK_NAME', value: 'core-k8s-cicd-sl-calico'),
              string(name: 'OS_PROJECT_NAME', value: 'mcp-scale-dev'),
              string(name: 'STACK_INSTALL', value: 'core,k8s,stacklight'),
          ],
          propagate: true,
          wait: true,
      )
  }
}
