#!/bin/bash

#Workaround for PROD-19642
PROFILE=mirantis
maas-region apikey --username=$PROFILE > /tmp/api.key
maas login $PROFILE http://localhost:5240/MAAS/api/2.0 - < /tmp/api.key
# maas $PROFILE boot-source-selections create 1 os="ubuntu" release="xenial" arches="amd64" subarches="*" labels="*"
# maas $PROFILE boot-resources import

# Workaround for PROD-19296
#while maas $PROFILE boot-resources read | jq '.[]| {type:.type}' --compact-output | grep -v -m 1 Synced > /dev/null  ; do echo "Images are not Synced yet"; done
#while (maas mirantis maas set-config name=default_distro_series value=xenial | grep "is not a valid"); do sleep 5; done
# Add static IP addresses from dhcp_snippets 
salt-call state.sls maas.region

#Renew IP addresses for all nodes to get an IPs from MAAS
salt "*" cmd.run "dhclient -r ens4; dhclient ens4"

apt -y install python3-novaclient/xenial-updates

#Temp fix for https://gerrit.mcp.mirantis.net/#/c/20105/ patch
#sed -i.bak "/power_parameters_power_address/d" /srv/salt/env/prd/_modules/maas.py
#salt-call saltutil.sync_all

salt-call state.sls maas.machines
salt-call maas.process_machines
salt-call state.sls maas.machines.wait_for_ready
salt-call state.sls maas.machines.assign_ip
salt-call maas.deploy_machines
salt-call state.sls maas.machines.wait_for_deployed
