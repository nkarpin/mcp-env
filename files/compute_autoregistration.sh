#!/bin/bash

config_host=$(salt-call grains.get master --out=newline_values_only)
os_codename=$(salt-call grains.get oscodename --out=newline_values_only)
node_network01_ip=$(salt-call grains.get ip4_interfaces:one1 --out=newline_values_only)
node_network01_iface="one1"
node_hostname=$(salt-call grains.get host --out=newline_values_only)
cluster_name="$1"



declare -A vars
vars=(
    ["node_master_ip"]=$config_host
    ["node_os"]=${os_codename}
    ["node_deploy_ip"]=${node_network01_ip}
    ["node_deploy_iface"]=${node_network01_iface}
    ["node_control_ip"]=${node_network01_ip}
    ["node_control_iface"]=${node_network01_iface}
#    ["node_tenant_ip"]=${node_network03_ip}
#    ["node_tenant_iface"]=${node_network03_iface}
#    ["node_external_ip"]=${node_network04_ip}
#    ["node_external_iface"]=${node_network04_iface}
#    ["node_baremetal_ip"]=${node_network05_ip}
#    ["node_baremetal_iface"]=${node_network05_iface}
#    ["node_baremetal_hwaddress"]=${node_network05_hwaddress}
#    ["node_domain"]=$node_domain
    ["node_cluster"]=$cluster_name
    ["node_hostname"]=$node_hostname
)

data=""; i=0
for key in "${!vars[@]}"; do
    data+="\"${key}\": \"${vars[${key}]}\""
    i=$(($i+1))
    if [ $i -lt ${#vars[@]} ]; then
        data+=", "
    fi
done

salt-call event.send "reclass/minion/classify" "{$data}"

sleep 5

salt-call saltutil.sync_all
salt-call mine.flush
salt-call mine.update
