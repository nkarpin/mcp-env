import sys
from junitparser import TestCase, TestSuite, JUnitXml, Skipped, Error


xml = JUnitXml(sys.argv[1])
xml2 =  JUnitXml()
result = xml.fromfile(sys.argv[1])

case_name_count = 0
case_name_orig_prev = ''
for suite_orig in result:
    suite_new = TestSuite()
    suite_new.errors = suite_orig.errors
    suite_new.failures = suite_orig.failures
    suite_new.skipped = suite_orig.skipped
    suite_new.time = suite_orig.time
    suite_new.timestamp = suite_orig.timestamp
    suite_new.tests = suite_orig.tests
    for case_orig in suite_orig:
        if case_name_orig_prev == '' or case_name_orig_prev != case_orig.name:
            case_name = case_orig.name
            case_name_count = 0
        else:
            case_name_count += 1
            case_name = '{}-{}'.format(case_orig.name, case_name_count)
        case_new = TestCase(case_name)
        case_new.time = case_orig.time
        case_new.classname = case_orig.classname
        suite_new.add_testcase(case_new)
        print('Add {}.{}'.format(case_orig.classname, case_name))
        case_name_orig_prev = case_orig.name
    xml2.add_testsuite(suite_new)
print ('Write junit xml to {}'.format(sys.argv[2]))
xml2.write(sys.argv[2]) # Writes back to file
