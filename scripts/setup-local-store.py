"""Run inside LocalStack. Create the configuration table without changing existing data."""
import json
import os
import subprocess

environment = dict(os.environ, AWS_ACCESS_KEY_ID="test", AWS_SECRET_ACCESS_KEY="test",
                   AWS_SESSION_TOKEN="", AWS_PAGER="", AWS_EC2_METADATA_DISABLED="true")
base = ["aws", "--endpoint-url=http://localhost:4566", "--region=us-east-1", "--output=json", "dynamodb"]
name = "webhook-configuration"

def call(*args):
    return subprocess.run(base + list(args), env=environment, text=True, capture_output=True)

created = False
result = call("describe-table", "--table-name", name)
if result.returncode:
    if "ResourceNotFoundException" not in result.stderr:
        raise RuntimeError("Could not inspect local configuration table: " + result.stderr)
    result = call("create-table", "--table-name", name, "--billing-mode", "PAY_PER_REQUEST",
                  "--key-schema", "AttributeName=PK,KeyType=HASH", "AttributeName=SK,KeyType=RANGE",
                  "--attribute-definitions", "AttributeName=PK,AttributeType=S", "AttributeName=SK,AttributeType=S")
    if result.returncode: raise RuntimeError("Could not create local configuration table: " + result.stderr)
    created = True
    waiter = call("wait", "table-exists", "--table-name", name)
    if waiter.returncode: raise RuntimeError("Local configuration table did not become available")
    result = call("describe-table", "--table-name", name)
    if result.returncode: raise RuntimeError("Could not verify local configuration table")
table = json.loads(result.stdout)["Table"]
assert {entry["AttributeName"]: entry["KeyType"] for entry in table["KeySchema"]} == {"PK": "HASH", "SK": "RANGE"}, "Existing table key schema mismatch; no data changed"
assert {entry["AttributeName"]: entry["AttributeType"] for entry in table["AttributeDefinitions"]} == {"PK": "S", "SK": "S"}, "Existing table key types mismatch"
print("PASS: local configuration table created (it was missing)." if created
      else "PASS: existing local configuration table validated; records preserved.")
