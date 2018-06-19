String project_name = 'mcp-scale'

node ('python'){
  stage('Run nightly tests'){
    build(
          job: 'create-mcp-env',
          parameters: [
              string(name: 'STACK_NAME', value: 'night-os-cicd-sl-oc'),
              string(name: 'OS_PROJECT_NAME', value: project_name),
              string(name: 'OS_AZ', value: project_name),
              string(name: 'STACK_INSTALL', value: 'core,cicd,openstack,stacklight,opencontrail'),
              booleanParam( name: 'DELETE_STACK', value: true),
              booleanParam( name: 'MAAS_ENABLE', value: false),
              booleanParam( name: 'RUN_TESTS', value: true),
              booleanParam( name: 'REPORT_CLUSTER_DEPLOYMENT_TO_TESTRAIL', value: false),
              booleanParam( name: 'REPORT_RALLY_RESULTS_TO_TESTRAIL', value: false),
              booleanParam( name: 'REPORT_RALLY_RESULTS_TO_SCALE', value: false),
          ],
          propagate: true,
          wait: true,
      )
  }
}
