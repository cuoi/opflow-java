Subscriber story

Lifecycle: 
Before:
Given a PubsubHandler named 'pubsub2' with properties file: 'pubsub-subscriber.properties'
Then the PubsubHandler named 'pubsub2' connection is 'opened'
After:
Outcome: ANY
When I close PubsubHandler named 'pubsub2'
Then the PubsubHandler named 'pubsub2' connection is 'closed'

Scenario: Simple Publisher/Subscriber

When I purge subscriber in PubsubHandler named 'pubsub2'
Then subscriber in PubsubHandler named 'pubsub2' has '0' messages
Given a subscriber named 'EchoJsonObject' in PubsubHandler named 'pubsub2'
When I publish '10000' random messages to subscriber 'EchoJsonObject' on PubsubHandler named 'pubsub2'
And waiting for subscriber of PubsubHandler(pubsub2) finish
Then PubsubHandler named 'pubsub2' receives '10000' messages
And subscriber in PubsubHandler named 'pubsub2' has '0' messages

