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
}
