#!/usr/bin/env python3

import argparse
import json
from pathlib import Path
import sys


FIELD_CANDIDATES = {
    "management_ec2_public_ip": ["management_ec2_public_ip", "management_public_ip", "bastion_public_ip"],
    "eks_cluster_name": ["eks_cluster_name", "cluster_name"],
}


def load_outputs(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def read_output_value(outputs: dict, field: str) -> str:
    candidates = FIELD_CANDIDATES.get(field, [field])
    for candidate in candidates:
        node = outputs.get(candidate)
        if isinstance(node, dict) and "value" in node:
            value = node["value"]
        elif node is not None:
            value = node
        else:
            continue
        if value is None:
            continue
        return str(value)
    return "<missing>"


def write_inventory(path: Path, host_ip: str, remote_user: str) -> None:
    content = "\n".join([
        "[management]",
        f"management ansible_host={host_ip} ansible_user={remote_user}",
        "",
    ])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate Ansible inventory from Terraform output JSON.")
    parser.add_argument("--terraform-output", required=True, help="Path to terraform output JSON file")
    parser.add_argument("--inventory", help="Inventory path to write")
    parser.add_argument("--remote-user", default="ubuntu", help="SSH user for the management host")
    parser.add_argument("--public-ip", help="Override public IP instead of reading terraform output")
    parser.add_argument("--print-field", choices=sorted(FIELD_CANDIDATES.keys()), help="Print a single field and exit")
    args = parser.parse_args()

    outputs = load_outputs(Path(args.terraform_output))

    if args.print_field:
        print(read_output_value(outputs, args.print_field))
        return 0

    if not args.inventory:
        parser.error("--inventory is required unless --print-field is used")

    host_ip = args.public_ip or read_output_value(outputs, "management_ec2_public_ip")
    if host_ip == "<missing>":
        print("Could not resolve management host public IP from Terraform outputs.", file=sys.stderr)
        return 1

    write_inventory(Path(args.inventory), host_ip, args.remote_user)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())