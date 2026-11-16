#!/usr/bin/env python3
"""
Intelligent Postman Collection Merger
Merges multiple Postman collections into one comprehensive collection with edge cases.
"""

import json
import sys
from pathlib import Path
from typing import Any

def deep_merge(base: dict, override: dict, path: str = "") -> dict:
    """Deep merge two dictionaries, collecting conflicts."""
    result = base.copy()
    conflicts = []
    
    for key, value in override.items():
        new_path = f"{path}.{key}" if path else key
        if key in result:
            if isinstance(result[key], dict) and isinstance(value, dict):
                result[key] = deep_merge(result[key], value, new_path)
            elif isinstance(result[key], list) and isinstance(value, list):
                # Merge lists, avoiding duplicates
                seen = set()
                merged = []
                for item in result[key] + value:
                    item_str = json.dumps(item, sort_keys=True)
                    if item_str not in seen:
                        seen.add(item_str)
                        merged.append(item)
                result[key] = merged
            else:
                # Conflict - prefer override
                conflicts.append(new_path)
                result[key] = value
        else:
            result[key] = value
    
    if conflicts:
        print(f"[INFO] Conflicting keys resolved (overridden): {', '.join(conflicts[:5])}{'...' if len(conflicts) > 5 else ''}")
    
    return result

def merge_collections(collection_paths: list[str], output_path: str) -> dict:
    """Merge multiple Postman collections into one."""
    
    merged = {
        "info": {
            "name": "Bhukkad - All APIs Comprehensive Collection",
            "description": "Intelligently merged Postman collection containing all Bhukkad APIs with comprehensive edge case coverage. "
                          "Includes: Identity, Catalog, Commerce, Engagement, Order, Payment, Delivery, Admin, Gateway, "
                          "Chat Support System, Anti-Fraud Engine, and real-time live streams.\n\n"
                          "**Organizations:**\n"
                          "- Authentication & User Management\n"
                          "- Restaurant & Menu Operations\n"
                          "- Order Placement & Tracking\n"
                          "- Payment Processing\n"
                          "- Delivery Management\n"
                          "- Live Order Tracking (SSE/WebSocket)\n"
                          "- Chat Support System (Restaurant Operations)\n"
                          "- Anti-Fraud Detection Engine\n"
                          "- Admin Management\n\n"
                          "**Edge Case Categories:**\n"
                          "1. Validation Errors (400/401/403/404)\n"
                          "2. Business Rule Violations\n"
                          "3. Data Boundary Testing\n"
                          "4. Concurrency Scenarios\n"
                          "5. Error Recovery Paths",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
            "version": "2.0.0"
        },
        "variable": [
            {"key": "base_url", "value": "http://localhost:8080", "type": "string"},
            {"key": "order_base_url", "value": "http://localhost:8083", "type": "string"},
            {"key": "jwt_token", "value": "", "type": "string"},
            {"key": "customer_id", "value": "1", "type": "string"},
            {"key": "restaurant_id", "value": "1", "type": "string"},
            {"key": "order_id", "value": "1001", "type": "string"},
            {"key": "agent_id", "value": "1", "type": "string"},
            {"key": "session_id", "value": "", "type": "string"},
            {"key": "fraud_case_id", "value": "", "type": "string"},
            {"key": "payment_id", "value": "1", "type": "string"},
            {"key": "menu_item_id", "value": "1", "type": "string"},
            {"key": "idempotency_key", "value": "", "type": "string"}
        ],
        "event": [
            {
                "listen": "prerequest",
                "script": {
                    "type": "text/javascript",
                    "exec": [
                        "// Auto-inject Bearer token for authenticated requests",
                        "const path = (typeof pm.request.url.getPath === 'function') ? pm.request.url.getPath() : String(pm.request.url).replace(/^https?:\\/\\/[^/]+/, '').split('?')[0];",
                        "const isHealth = path.includes('/health') || path.startsWith('/actuator/health') || path.includes('ping');",
                        "const noAuth = pm.variables.get('noAuth') === 'true';",
                        "const token = pm.environment.get('jwt_token') || pm.variables.get('accessToken');",
                        "if (!noAuth && !isHealth && !pm.request.headers.has('Authorization') && token) {",
                        "    pm.request.headers.upsert({ key: 'Authorization', value: 'Bearer ' + token });",
                        "}",
                        "",
                        "// Generate idempotency key",
                        "pm.variables.set('idempotencyKey', pm.variables.replaceIn('{{$guid}}'));",
                        "",
                        "// Generate fresh IDs for new entities",
                        "if (!pm.variables.get('session_id') || pm.variables.get('session_id') === '') {",
                        "    pm.variables.set('session_id', pm.variables.replaceIn('{{$guid}}'));",
                        "}"
                    ]
                }
            },
            {
                "listen": "test",
                "script": {
                    "type": "text/javascript",
                    "exec": [
                        "pm.test('Response time < 5s', () => pm.expect(pm.response.responseTime).to.be.below(5000));",
                        "",
                        "if (pm.response.code >= 400) {",
                        "    pm.test('Error response has traceId', () => {",
                        "        const json = pm.response.json();",
                        "        pm.expect(json.traceId || json.correlationId).to.exist;",
                        "    });",
                        "}",
                        "",
                        "if (pm.response.code === 200 || pm.response.code === 201) {",
                        "    pm.test('Valid JSON response', () => {",
                        "        pm.response.to.have.jsonContent;",
                        "    });",
                        "}"
                    ]
                }
            }
        ],
        "item": []
    }
    
    # Track all item names to avoid duplicates
    seen_names = set()
    folder_structure = {}
    
    for collection_path in collection_paths:
        path = Path(collection_path)
        print(f"[INFO] Processing: {path.name}")
        
        try:
            with open(path, 'r') as f:
                data = json.load(f)
        except json.JSONDecodeError as e:
            print(f"[WARN] Invalid JSON in {path.name}: {e}")
            continue
        except FileNotFoundError:
            print(f"[WARN] File not found: {path.name}")
            continue
        
        # Merge info
        if 'info' in data:
            if 'variable' in data:
                # Variables could be a list or dict depending on Postman format
                data_vars = data['variable']
                if isinstance(data_vars, list):
                    # Convert to dict for merging
                    data_vars_dict = {v['key']: v for v in data_vars if 'key' in v}
                    merged_vars_dict = {v['key']: v for v in merged['variable']}
                    result = deep_merge(merged_vars_dict, data_vars_dict)
                    merged['variable'] = list(result.values())
                else:
                    merged['variable'] = deep_merge(merged['variable'], data_vars)
        
        # Process items/folders
        items = []
        if 'item' in data:
            if isinstance(data['item'], list):
                items = data['item']
            elif isinstance(data['item'], dict) and 'item' in data['item']:
                items = data['item']['item']
        
        for item in items:
            process_item(item, merged['item'], seen_names, folder_structure, path.stem)
    
    return merged

def process_item(item: dict, target_list: list, seen_names: set, folder_structure: dict, source_name: str):
    """Process an item from a collection and add to target list."""
    name = item.get('name', 'Unnamed')
    
    if 'item' in item:
        # This is a folder
        folder_name = f"{source_name} - {name}"
        if folder_name not in folder_structure:
            folder_structure[folder_name] = []
            target_list.append({
                "name": folder_name,
                "item": []
            })
        else:
            folder_name = f"{source_name} - {name} (alt)"
        
        for sub_item in item['item']:
            process_item(sub_item, target_list[-1]['item'], seen_names, folder_structure, source_name)
    else:
        # This is a request
        unique_name = f"{name} ({source_name})"
        if unique_name not in seen_names:
            seen_names.add(unique_name)
            target_list.append(item)

def add_edge_case_tests(merged: dict) -> dict:
    """Add comprehensive edge case tests for each major API category."""
    
    edge_case_folders = [
        {
            "name": "Edge Cases & Negative Testing",
            "item": [
                {
                    "name": "Authentication Edge Cases",
                    "item": [
                        {
                            "name": "401 - Missing Authorization Header",
                            "request": {
                                "method": "GET",
                                "header": [],
                                "url": {"raw": "{{base_url}}/api/v1/health/ping"},
                                "description": "Should return 401 Unauthorized when no token provided"
                            }
                        },
                        {
                            "name": "401 - Invalid JWT Token",
                            "request": {
                                "method": "GET",
                                "header": {"Authorization": "Bearer invalid_token_12345"},
                                "url": {"raw": "{{base_url}}/api/v1/identity/customers/me"},
                                "description": "Should return 401 for malformed JWT"
                            }
                        },
                        {
                            "name": "403 - Insufficient Role Permissions",
                            "request": {
                                "method": "GET",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/admin/users"},
                                "description": "Customer cannot access admin endpoints"
                            }
                        }
                    ]
                },
                {
                    "name": "Data Validation Edge Cases",
                    "item": [
                        {
                            "name": "400 - Invalid Email Format",
                            "request": {
                                "method": "POST",
                                "header": {"Content-Type": "application/json"},
                                "url": {"raw": "{{base_url}}/api/v1/identity/register"},
                                "body": {"mode": "raw", "raw": '{"email": "not-an-email", "password": "test123"}'},
                                "description": "Email validation should reject invalid format"
                            }
                        },
                        {
                            "name": "400 - Weak Password",
                            "request": {
                                "method": "POST",
                                "header": {"Content-Type": "application/json"},
                                "url": {"raw": "{{base_url}}/api/v1/identity/register"},
                                "body": {"mode": "raw", "raw": '{"email": "test@example.com", "password": "123"}'},
                                "description": "Password should meet complexity requirements"
                            }
                        },
                        {
                            "name": "400 - Negative Quantity",
                            "request": {
                                "method": "POST",
                                "header": {"Content-Type": "application/json", "Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{order_base_url}}/api/v1/orders"},
                                "body": {"mode": "raw", "raw": '{"customerId": {{customer_id}}, "items": [{"menuItemId": 1, "quantity": -1}]}'},
                                "description": "Quantity cannot be negative"
                            }
                        },
                        {
                            "name": "400 - Zero Quantity",
                            "request": {
                                "method": "POST",
                                "header": {"Content-Type": "application/json", "Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{order_base_url}}/api/v1/orders"},
                                "body": {"mode": "raw", "raw": '{"customerId": {{customer_id}}, "items": [{"menuItemId": 1, "quantity": 0}]}'},
                                "description": "Quantity cannot be zero"
                            }
                        }
                    ]
                },
                {
                    "name": "Resource Not Found Edge Cases",
                    "item": [
                        {
                            "name": "404 - Non-existent Order ID",
                            "request": {
                                "method": "GET",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/orders/999999"},
                                "description": "Should return 404 for non-existent order"
                            }
                        },
                        {
                            "name": "404 - Non-existent Customer",
                            "request": {
                                "method": "GET",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/identity/customers/999999"},
                                "description": "Should return 404 for non-existent customer"
                            }
                        }
                    ]
                },
                {
                    "name": "File Upload Edge Cases",
                    "item": [
                        {
                            "name": "400 - File Too Large",
                            "request": {
                                "method": "POST",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/chat/sessions/{{session_id}}/messages"},
                                "body": {"mode": "formdata", "formdata": [
                                    {"key": "content", "value": "Test", "type": "text"},
                                    {"key": "attachments", "type": "file", "multiple": False}
                                ]},
                                "description": "Should reject files exceeding size limit"
                            }
                        },
                        {
                            "name": "400 - Invalid MIME Type",
                            "request": {
                                "method": "POST",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/chat/sessions/{{session_id}}/messages"},
                                "body": {"mode": "formdata", "formdata": [
                                    {"key": "content", "value": "Test file", "type": "text"},
                                    {"key": "attachments", "type": "file"}
                                ]},
                                "description": "Should reject files with disallowed MIME types"
                            }
                        }
                    ]
                },
                {
                    "name": "Concurrency Edge Cases",
                    "item": [
                        {
                            "name": "409 - Duplicate Order Submission",
                            "request": {
                                "method": "POST",
                                "header": {"Content-Type": "application/json", "Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/orders"},
                                "body": {"mode": "raw", "raw": '{"customerId": {{customer_id}}, "idempotencyKey": "{{idempotency_key}}"}'},
                                "description": "Second request with same idempotency key should return 409"
                            }
                        }
                    ]
                },
                {
                    "name": "Rate Limiting Edge Cases",
                    "item": [
                        {
                            "name": "429 - Too Many Requests",
                            "request": {
                                "method": "GET",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/identity/customers/me"},
                                "description": "After rate limit exceeded, should return 429"
                            }
                        }
                    ]
                },
                {
                    "name": "Fraud Detection Edge Cases",
                    "item": [
                        {
                            "name": "Simulate Duplicate Attachment Fraud",
                            "request": {
                                "method": "POST",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/chat/sessions/{{session_id}}/messages"},
                                "body": {"mode": "formdata", "formdata": [
                                    {"key": "content", "value": "Sending same photo again for claim", "type": "text"},
                                    {"key": "attachments", "type": "file"}
                                ]},
                                "description": "Same image fingerprint should trigger duplicate fraud detection"
                            }
                        },
                        {
                            "name": "Simulate Suspicious Keyword Detection",
                            "request": {
                                "method": "POST",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/chat/sessions/{{session_id}}/messages"},
                                "body": {"mode": "formdata", "formdata": [
                                    {"key": "content", "value": "FAKE ORDER: Never received, want full refund NOW!", "type": "text"}
                                ]},
                                "description": "Multiple suspicious keywords should increase fraud score"
                            }
                        },
                        {
                            "name": "Simulate Missing EXIF Detection",
                            "request": {
                                "method": "POST",
                                "header": {"Authorization": "Bearer {{jwt_token}}"},
                                "url": {"raw": "{{base_url}}/api/v1/chat/sessions/{{session_id}}/messages"},
                                "body": {"mode": "formdata", "formdata": [
                                    {"key": "content", "value": "Photo without proper metadata", "type": "text"},
                                    {"key": "attachments", "type": "file"}
                                ]},
                                "description": "Images without EXIF should trigger higher fraud score"
                            }
                        }
                    ]
                }
            ]
        }
    ]
    
    for folder in edge_case_folders:
        merged['item'].append(folder)
    
    return merged

def main():
    """Main entry point."""
    collection_files = [
        "postman/Bhukkad-API.postman_collection.json",
        "postman/Bhukkad-API.postman_environment.json",
        "postman/Bhukkad-Order-Edge-Cases.postman_collection.json",
        "postman/Bhukkad-Order-All-Edge-Cases.postman_collection.json",
        "postman/Bhukkad_API_Postman_Collection.json",
        "postman/Bhukkad_Comprehensive_API_Collection.postman_collection.json"
    ]
    
    output_file = "postman/Bhukkad_All_APIs_Comprehensive.postman_collection.json"
    
    print(f"[INFO] Merging {len(collection_files)} Postman collections...")
    
    merged = merge_collections(collection_files, output_file)
    merged = add_edge_case_tests(merged)
    
    with open(output_file, 'w') as f:
        json.dump(merged, f, indent=2)
    
    # Count requests
    def count_requests(items):
        count = 0
        for item in items:
            if 'item' in item:
                count += count_requests(item['item'])
            else:
                count += 1
        return count
    
    request_count = count_requests(merged['item'])
    
    print(f"[SUCCESS] Merged collection saved to: {output_file}")
    print(f"[INFO] Total requests: {request_count}")
    print(f"[INFO] Variables: {len(merged['variable'])}")
    print(f"[INFO] Event hooks: {len(merged['event'])}")

if __name__ == "__main__":
    main()