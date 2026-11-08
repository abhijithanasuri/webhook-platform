"""Run inside the LocalStack container. Creates and removes isolated test resources."""
import json
import os
import subprocess
import uuid

environment = dict(os.environ, AWS_ACCESS_KEY_ID="test", AWS_SECRET_ACCESS_KEY="test",
                   AWS_SESSION_TOKEN="", AWS_PAGER="", AWS_EC2_METADATA_DISABLED="true")

def aws(service, operation, *arguments):
    result = subprocess.run(
        ["aws", "--endpoint-url=http://localhost:4566", "--region=us-east-1",
         "--output=json", service, operation, *arguments],
        env=environment, check=True, text=True, capture_output=True,
    )
    return json.loads(result.stdout) if result.stdout.strip() else {}
name = "webhook-setup-check-" + uuid.uuid4().hex[:12]
table_created = False
queue_url = None
try:
    aws("dynamodb", "create-table", "--table-name", name,
        "--key-schema", "AttributeName=PK,KeyType=HASH",
        "--attribute-definitions", "AttributeName=PK,AttributeType=S",
        "--billing-mode", "PAY_PER_REQUEST")
    table_created = True
    aws("dynamodb", "wait", "table-exists", "--table-name", name)
    item = {"PK": {"S": "setup-check"}, "value": {"S": "synthetic-only"}}
    aws("dynamodb", "put-item", "--table-name", name, "--item", json.dumps(item))
    result = aws("dynamodb", "get-item", "--table-name", name,
                 "--key", json.dumps({"PK": {"S": "setup-check"}}), "--consistent-read")
    assert result.get("Item") == item, "DynamoDB read did not match write"
    print("PASS: DynamoDB create/write/consistent-read", flush=True)
    queue_url = aws("sqs", "create-queue", "--queue-name", name)["QueueUrl"]
    aws("sqs", "send-message", "--queue-url", queue_url, "--message-body", "synthetic-setup-check")
    messages = aws("sqs", "receive-message", "--queue-url", queue_url, "--wait-time-seconds", "10").get("Messages", [])
    assert len(messages) == 1 and messages[0]["Body"] == "synthetic-setup-check", "SQS message mismatch"
    aws("sqs", "delete-message", "--queue-url", queue_url, "--receipt-handle", messages[0]["ReceiptHandle"])
    assert not aws("sqs", "receive-message", "--queue-url", queue_url, "--wait-time-seconds", "2").get("Messages"), "Queue not empty"
    print("PASS: SQS create/send/receive/delete", flush=True)
finally:
    # Only delete resources allocated by this invocation; never existing project data.
    if queue_url:
        aws("sqs", "delete-queue", "--queue-url", queue_url)
    if table_created:
        aws("dynamodb", "delete-table", "--table-name", name)
print(json.dumps({"result": "PASS", "temporary_resources_cleaned": True}))
