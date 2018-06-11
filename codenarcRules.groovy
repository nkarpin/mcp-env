ruleset {
  ruleset('rulesets/basic.xml')
  ruleset('rulesets/braces.xml')
  ruleset('rulesets/concurrency.xml')
  ruleset('rulesets/convention.xml') {
    // Don't need due to code readablilty
    exclude 'NoDef'
  }
  ruleset('rulesets/design.xml') {
    // Don't need due to code readablilty
    exclude 'BuilderMethodWithSideEffects'
    // Sometimes nested loop is cleaner than extracting a new method
    exclude 'NestedForLoop'
    // TBD
    exclude 'ImplementationAsType'
  }
  ruleset('rulesets/dry.xml') {
    DuplicateNumberLiteral {
      ignoreNumbers = '0,1,2,3'
    }
    DuplicateStringLiteral {
      ignoreStrings = ', ,null,rm -rf archive,REFSPEC,OPENSTACK_ENVIRONMENT,STACK_NAME,STACK_INSTALL,OS_PROJECT_NAME,rm -f ,k8s_keepalived_vip_vrid,mcp_version,presales'
    }
  }
  ruleset('rulesets/exceptions.xml'){
    // Not necessarily an issue
    exclude 'CatchException'
    // Not necessarily an issue
    exclude 'ThrowRuntimeException'
  }
  ruleset('rulesets/formatting.xml'){
    // Don't need due to code readablilty
    exclude 'ConsecutiveBlankLines'
    // TBD
    exclude 'LineLength'
    // TBD: Causes false positive alerts
    exclude 'SpaceAfterClosingBrace'
    // Enforce at least one space after map entry colon
    SpaceAroundMapEntryColon {
            characterAfterColonRegex = /\s/
            characterBeforeColonRegex = /./
    }
    // TBD: Causes false positive alerts
    exclude 'SpaceBeforeOpeningBrace'
  }
  ruleset('rulesets/generic.xml')
  ruleset('rulesets/grails.xml')
  ruleset('rulesets/groovyism.xml'){
    // Not necessarily an issue
    exclude 'GStringExpressionWithinString'
  }
  ruleset('rulesets/imports.xml')
  ruleset('rulesets/jdbc.xml')
  ruleset('rulesets/junit.xml')
  ruleset('rulesets/logging.xml'){
    // Can't be used in jenklins pipelines
    exclude 'Println'
  }
  ruleset('rulesets/naming.xml'){
    // Don't need due to code readablilty
    exclude 'FactoryMethodName'
    // Don't need due to code readablilty
    exclude 'VariableName'
  }
  ruleset('rulesets/security.xml'){
    // Don't need to satisfy the Java Beans specification
    exclude 'JavaIoPackageAccess'
  }
  ruleset('rulesets/serialization.xml')
  // TBD: Huge functions should be rewritten
  ruleset('rulesets/size.xml'){
    exclude 'AbcMetric'
    exclude 'MethodSize'
    exclude 'NestedBlockDepth'
    // Not necessarily an issue
    exclude 'ParameterCount'
    //We don't need it for mcp-create-env
    exclude 'CyclomaticComplexity'
  }
  ruleset('rulesets/unnecessary.xml'){
    // Don't need due to code readablilty
    exclude 'UnnecessaryDefInVariableDeclaration'
    // TBD: Huge amount of warnings
    exclude 'UnnecessaryGetter'
    // Not necessarily an issue
    exclude 'UnnecessaryReturnKeyword'
  }
  ruleset('rulesets/unused.xml')
}
