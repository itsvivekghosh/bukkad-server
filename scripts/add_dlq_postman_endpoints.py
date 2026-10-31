#!/usr/bin/env python3
"""
Add DLQ Management API endpoints to the Bhukkad-API Postman collection.
Adds 5 DLQ endpoints to the '07 - Admin' folder with test scripts.
"""

import json
import uuid
from pathlib import Path

COLLECTION_PATH = Path("postman/Bhukkad-API.postman_collection.json")


def new_id():
    return str(uuid.uuid4())


def endpoint(name, method, url, test_script, headers=None):
    ep = {
        "name": name,
        "request": {
            "method": method,
            "header": headers or [],
            "url": url,
        },
        "response": [],
        "event": [
            {
                "listen": "test",
                "script": {
                    "type": "text/javascript",
                    "exec": test_script,
                },
            }
        ],
    }
    return ep


# DLQ endpoints to add to the Admin folder
dlq_endpoints = [
    endpoint(
        "List Dead Letters",
        "GET",
        "{{baseUrl}}/api/v1/admin/dlq?limit=100",
        [
            "pm.test('Status is 200', () => pm.expect(pm.response.code).to.eql(200));",
            "const json = pm.response.json();",
            "pm.test('Response has events array', () => pm.expect(json).to.have.property('events'));",
            "pm.test('events is an array', () => pm.expect(json.events).to.be.an('array'));",
            "pm.test('Response has pendingCount', () => pm.expect(json).to.have.property('pendingCount'));",
            "pm.test('Response has fetched count', () => pm.expect(json).to.have.property('fetched'));",
        ],
    ),
    endpoint(
        "Get Pending Count",
        "GET",
        "{{baseUrl}}/api/v1/admin/dlq/count",
        [
            "pm.test('Status is 200', () => pm.expect(pm.response.code).to.eql(200));",
            "const json = pm.response.json();",
            "pm.test('Response has pendingCount', () => pm.expect(json).to.have.property('pendingCount'));",
            "pm.test('pendingCount is a number', () => pm.expect(json.pendingCount).to.be.a('number'));",
        ],
    ),
    endpoint(
        "Reprocess Single Event",
        "POST",
        "{{baseUrl}}/api/v1/admin/dlq/reprocess",
        [
            "pm.test('Status in [200, 400, 500]', () => pm.expect(pm.response.code).to.be.oneOf([200, 400, 500]));",
            "const json = pm.response.json();",
            "pm.test('Response has success field', () => pm.expect(json).to.have.property('success'));",
            "if (pm.response.code === 200) {",
            "    pm.test('success is true on 200', () => pm.expect(json.success).to.eql(true));",
            "    pm.test('Response has message', () => pm.expect(json).to.have.property('message'));",
            "    pm.test('Response has cacheKey', () => pm.expect(json).to.have.property('cacheKey'));",
            "}",
            "if (pm.response.code === 400) {",
            "    pm.test('success is false on 400', () => pm.expect(json.success).to.eql(false));",
            "}",
        ],
        headers=[
            {"key": "Content-Type", "value": "application/json"},
        ],
    ),
    endpoint(
        "Batch Reprocess Events",
        "POST",
        "{{baseUrl}}/api/v1/admin/dlq/reprocess/batch",
        [
            "pm.test('Status is 200', () => pm.expect(pm.response.code).to.eql(200));",
            "const json = pm.response.json();",
            "pm.test('Response has success count', () => pm.expect(json).to.have.property('success'));",
            "pm.test('Response has failed count', () => pm.expect(json).to.have.property('failed'));",
            "pm.test('Response has results array', () => pm.expect(json).to.have.property('results'));",
            "pm.test('results is an array', () => pm.expect(json.results).to.be.an('array'));",
        ],
        headers=[
            {"key": "Content-Type", "value": "application/json"},
        ],
    ),
    endpoint(
        "Skip DLQ Event",
        "DELETE",
        "{{baseUrl}}/api/v1/admin/dlq/skip?topic=test-event&partition=0&offset=0",
        [
            "pm.test('Status in [200, 403, 500]', () => pm.expect(pm.response.code).to.be.oneOf([200, 403, 500]));",
            "const json = pm.response.json();",
            "if (pm.response.code === 200) {",
            "    pm.test('success is true on 200', () => pm.expect(json.success).to.eql(true));",
            "    pm.test('message is present', () => pm.expect(json).to.have.property('message'));",
            "}",
        ],
    ),
]


def add_dlq_endpoints(collection_path):
    with open(collection_path, "r") as f:
        data = json.load(f)

    # Find Admin folder
    admin_folder = None
    for item in data.get("item", []):
        if "item" in item and item.get("name") == "07 - Admin":
            admin_folder = item
            break

    if admin_folder is None:
        raise RuntimeError("Admin folder '07 - Admin' not found in collection")

    # Add DLQ endpoints to Admin folder
    admin_folder["item"].extend(dlq_endpoints)

    # Write back
    with open(collection_path, "w") as f:
        json.dump(data, f, indent=2)

    print(f"Added {len(dlq_endpoints)} DLQ endpoints to '07 - Admin' folder")


if __name__ == "__main__":
    add_dlq_endpoints(COLLECTION_PATH)
