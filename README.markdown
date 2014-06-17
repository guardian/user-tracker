# User Tracker

Library for displaying current (logged in) users of your application.

Designed so as to be non-blocking on writes and reads, and to perform as few
database operations as possible in the background. (i.e., it's supposed to be
cheap to use!)

## Usage

### Persistence

Persistence allows your logged in user data to be propagated across a set of
servers.

Either implement the `Persistence` layer yourself or use the provided
`DynamoDBPersistence` with [DynamoDB](http://aws.amazon.com/dynamodb/).

```scala
val persistence = DynamoDBPersistence(
  client = new AmazonDynamoDBAsyncClient(),
  tableName = "application_users"
)
```

If using `DynamoDBPersistence` make sure to start the GC cycle:

```scala
DynamoDBPersistence.startGarbageCollection(persistence, frequency = 5 minutes)
```

### User Tracking

Create a single instance of `UserTracker` at application start up:

```scala
val userTracker = new UserTracker(
  persistence
)
```

Trigger the scheduled updates to the persistence layer at start up:

```scala
UserTracker.startPersistence(userTracker)
```

Tell the user tracker when you see a user:

```scala
userTracker.recordSeenNow(UserId("robert"))
```

Ask the user tracker for the currently active users:

```scala
val users = userTracker.currentlyActive
```

## License

Copyright Guardian News & Media. Licensed under Apache 2.0.
