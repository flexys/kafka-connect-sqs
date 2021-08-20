#!/usr/bin/env bash

# note: this script is totally untested and may not exactly work - this is more of a reference that may also happen to be executable
# also note: run `docker-compose up` first

# ensure queues exist - may need to run `aws configure --profile default` first
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name chirps-q
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name chirped-q

# restart tasks if they already exist (and inevitably crashed because the queues don't exist)
curl -X POST http://localhost:8083/connectors/sqs-sink-chirped/tasks/0/restart
curl -X POST http://localhost:8083/connectors/sqs-source-chirps/tasks/0/restart

# create tasks if they _don't_ already exist - if they do, these will just return 409
curl -X POST -H 'Content-Type: application/json' http://localhost:8083/connectors -d @demos/sqs-source-chirps.json
curl -X POST -H 'Content-Type: application/json' http://localhost:8083/connectors -d @demos/sqs-sink-chirped.json

# run these manually to test it works
# aws --endpoint-url=http://localhost:4566 sqs send-message --queue-url http://localstack:4566/000000000000/chirps-q --message-body 'cheep cheep cheep
# aws --endpoint-url=http://localhost:4566 sqs receive-message --queue-url http://localhost:4566/000000000000/chirped-q
