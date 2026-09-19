import xml.etree.ElementTree as ET
from xml.dom import minidom

tree = ET.parse('services/platform-lib/pom.xml')
root = tree.getroot()

# Register namespaces
namespaces = {'': 'http://maven.apache.org/POM/4.0.0'}
for prefix, uri in namespaces.items():
    ET.register_namespace(prefix, uri)

ns = '{http://maven.apache.org/POM/4.0.0}'

# Add pitest-junit5 dependency
deps = root.find(f'{ns}dependencies')
if deps is not None:
    dep = ET.SubElement(deps, f'{ns}dependency')
    group = ET.SubElement(dep, f'{ns}groupId')
    group.text = 'org.pitest'
    artifact = ET.SubElement(dep, f'{ns}artifactId')
    artifact.text = 'pitest-junit5'
    version = ET.SubElement(dep, f'{ns}version')
    version.text = '1.15.0'
    scope = ET.SubElement(dep, f'{ns}scope')
    scope.text = 'test'

# Add pitest profile
profiles = root.find(f'{ns}profiles')
if profiles is None:
    profiles = ET.SubElement(root, f'{ns}profiles')

profile = ET.SubElement(profiles, f'{ns}profile')
pid = ET.SubElement(profile, f'{ns}id')
pid.text = 'mutation-testing'
build = ET.SubElement(profile, f'{ns}build')
plugins = ET.SubElement(build, f'{ns}plugins')
plugin = ET.SubElement(plugins, f'{ns}plugin')
group_id = ET.SubElement(plugin, f'{ns}groupId')
group_id.text = 'org.pitest'
artifact_id = ET.SubElement(plugin, f'{ns}artifactId')
artifact_id.text = 'pitest-maven'
version_elem = ET.SubElement(plugin, f'{ns}version')
version_elem.text = '1.15.0'
configuration = ET.SubElement(plugin, f'{ns}configuration')
config = ET.SubElement(configuration, f'{ns}configuration')
target_classes = ET.SubElement(config, f'{ns}targetClasses')
target_classes.text = 'com.bhukkad.common.*'
target_tests = ET.SubElement(config, f'{ns}targetTests')
target_tests.text = 'com.bhukkad.common.*Test'
mutators = ET.SubElement(config, f'{ns}mutators')
mutator = ET.SubElement(mutators, f'{ns}mutator')
mutator.text = 'DEFAULTS'
timeout_factor = ET.SubElement(config, f'{ns}timeoutFactor')
timeout_factor.text = '1.5'
report_dir = ET.SubElement(config, f'{ns}outputDirectory')
report_dir.text = '${project.build.directory}/pit-reports'

# Write with proper formatting
xml_str = minidom.parseString(ET.tostring(root)).toprettyxml(indent='\t', encoding='UTF-8')
with open('services/platform-lib/pom.xml', 'wb') as f:
    f.write(xml_str)

print('Updated pom.xml')
