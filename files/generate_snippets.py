#!/usr/bin/env python

import os
import sys
import openstack
import json
from jinja2 import Template

def get_credentials():
    cred = {}
    cred['username'] = os.environ['OS_USERNAME']
    cred['auth_url'] = os.environ['OS_AUTH_URL']
    cred['password'] = os.environ['OS_PASSWORD']
    cred['project_name'] = os.environ['OS_PROJECT_NAME']
    return cred

dhcp_file = open ("/tmp/dhcp_snippets.yml.src", "w")
dhcp_file.write('''parameters:
  maas:
    region:
      dhcp_snippets:''')

machines_file = open ("/tmp/machines_template.yml.src", "w")

machines_file.write('''parameters:
  maas:
    region:
      machines:''')

dhcp_template = Template('''
        {{name}}-snippet:
          value: host {{name}} { hardware ethernet {{mac_addr}} ; fixed-address {{ip_addr}}; }
          description: Static IP address for {{name}} node
          enabled: true
          subnet: 10.10.0.0/16''')

machines_template = Template('''
        {{name}}:
          interface:
            mac: {{mac_addr}}
            mode: static
            ip: {{ip_addr}}
            subnet: 10.10.0.0/16
          power_parameters:
            power_type: nova
            power_nova_id: {{uuid}}
            power_os_tenantname: {{tenant_name}}
            power_os_username: {{os_username}}
            power_os_password: {{os_password}}
            power_os_authurl: {{os_authurl}}
          pxe_interface_mac: {{mac_addr}}''')

credentials = get_credentials()
conn = openstack.connect(**credentials)

subnet = conn.network.find_subnet(sys.argv[1]+"-net01",ignore_missing=True)
if subnet:
    conn.network.update_subnet(subnet, is_dhcp_enabled = 'false')

for server in conn.compute.servers(name=sys.argv[1]):
    try:
        address_list = server.addresses[sys.argv[1]+"-net01"]
        port_list = json.loads(json.dumps(address_list))
        if ( ( "cmp" in server.name ) or ( "gtw" in server.name ) or ( "kvm" in server.name ) ):
            server_name = server.name.split(".")[0]
            node_info = {
                'name': server_name,
                'mac_addr': port_list[0]['OS-EXT-IPS-MAC:mac_addr'],
                'ip_addr':port_list[0]['addr'],
                'uuid': server.id,
                'tenant_name': os.environ['OS_PROJECT_NAME'],
                'os_username': os.environ['OS_USERNAME'],
                'os_password': os.environ['OS_PASSWORD'],
                'os_authurl': os.environ['OS_AUTH_URL']
            }
            machines_file.write (machines_template.render(node_info))
            conn.compute.stop_server(server.id)
        else:
            node_info = {
                'name': server.name,
                'mac_addr': port_list[0]['OS-EXT-IPS-MAC:mac_addr'],
                'ip_addr':port_list[0]['addr']
            }
            dhcp_file.write(dhcp_template.render(node_info))
    except KeyError:
        print ("The server with name %s from another stack" % server.name)

dhcp_file.close()
machines_file.close()
