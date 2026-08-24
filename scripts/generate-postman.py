#!/usr/bin/env python3
"""
Generate a Postman v2.1 collection from the Bhukkad API catalog.

Usage: python3 scripts/generate-postman.py > postman/Bhukkad-API.postman_collection.json
"""

import json
import sys
import os
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from api_catalog import API_CATALOG, BODY_TEMPLATES

BASE_URL = "{{base_url}}"
AUTH_HEADERS = {
    "customer": "{{customer_token}}",
    "owner": "{{owner_token}}",
    "agent": "{{agent_token}}",
    "admin": "{{admin_token}}",
    None: None,
}


def build_request(spec):
    """Build a Postman request object from a catalog spec."""
    method = spec["method"]
    path = spec["path"]
    url = f"{BASE_URL}{path}"

    # Query params
    query_params = []
    if spec.get("query"):
        for key, value in spec["query"].items():
            query_params.append({"key": key, "value": str(value) if value is not None else ""})

    # Headers
    headers = [
        {"key": "Content-Type", "value": "application/json"},
        {"key": "Accept", "value": "application/json"},
    ]

    # Auth header
    auth = spec.get("auth")
    token_var = AUTH_HEADERS.get(auth)
    if token_var:
        headers.append({"key": "Authorization", "value": f"Bearer {token_var}"})

    # Custom headers from spec
    if spec.get("headers"):
        for key, value in spec["headers"].items():
            headers.append({"key": key, "value": value})

    # Body
    body = None
    body_ref = spec.get("body_template")
    content_type = spec.get("content_type", "json")
    if method in ("POST", "PUT", "PATCH") and body_ref and body_ref in BODY_TEMPLATES:
        template = BODY_TEMPLATES[body_ref]
        if content_type == "csv":
            body = {"mode": "raw", "raw": template}
        else:
            body = {"mode": "raw", "raw": json.dumps(template, indent=2)}
    elif method in ("POST", "PUT", "PATCH") and spec.get("body"):
        body = {"mode": "raw", "raw": json.dumps(spec["body"], indent=2)}

    return {
        "method": method,
        "header": headers,
        "body": body,
        "url": {
            "raw": url,
            "protocol": "http",
            "host": ["{{base_url}}"],
            "path": path.strip("/").split("/"),
            "query": query_params if query_params else None,
            "variable": [],
        },
        "description": spec.get("description", ""),
    }


def build_collection():
    # Group by catalog group
    groups = {}
    for spec in API_CATALOG:
        grp = spec.get("group", "General")
        if grp not in groups:
            groups[grp] = []
        groups[grp].append(spec)

    # Sort groups: use a preferred order, then alphabetical for the rest
    preferred = [
        "Health & Platform", "Authentication", "Customer", "Admin", "Restaurant",
        "Menu", "Cart", "Orders", "Payments", "Delivery", "Home Feed",
        "Search", "Referral", "Coupons", "Wallet", "Reviews", "Analytics",
        "Cuisines", "Gift Cards", "Disputes", "Inventory", "Security",
        "Validation", "Cache", "Not Found", "Growth & Operations",
        "Delivery Truth (V14)", "Scale Operations (V16)", "Trust & Compliance (V17)",
    ]
    all_groups = list(groups.keys())
    sorted_groups = [g for g in preferred if g in groups] + \
                    sorted([g for g in all_groups if g not in preferred])

    items = []
    for grp in sorted_groups:
        if grp not in groups:
            continue
        group_items = []
        for spec in groups[grp]:
            name = spec["name"]
            # Skip internal/setup entries
            if name.startswith("_setup") or name.startswith("_teardown"):
                continue
            request = build_request(spec)
            item = {
                "name": name,
                "request": request,
                "response": [],
            }
            # Expected status codes
            if spec.get("expected"):
                item["response"] = [{"name": f"Expected {spec['expected']}", "code": spec["expected"][0]}]
            group_items.append(item)
        items.append({
            "name": grp,
            "item": group_items,
        })

    collection = {
        "info": {
            "name": "Bhukkad API v1",
            "description": "Complete Bhukkad Food Delivery API — all endpoints from the API catalog",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
            "_exporter_id": "bhukkad",
        },
        "item": items,
        "variable": [
            {"key": "base_url", "value": "http://localhost:8080", "type": "string"},
            {"key": "customer_token", "value": "", "type": "string"},
            {"key": "owner_token", "value": "", "type": "string"},
            {"key": "agent_token", "value": "", "type": "string"},
            {"key": "admin_token", "value": "", "type": "string"},
        ],
        "auth": {
            "type": "bearer",
            "bearer": [{"key": "token", "value": "{{customer_token}}", "type": "string"}],
        },
    }

    return collection


if __name__ == "__main__":
    col = build_collection()
    output = os.environ.get("OUTPUT", "postman/Bhukkad-API.postman_collection.json")
    with open(output, "w") as f:
        json.dump(col, f, indent=2)
    print(f"Generated {output} with {sum(len(g['item']) for g in col['item'])} endpoints in {len(col['item'])} groups")