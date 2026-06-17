# MongoDB Operations Guide

This document provides a reference for performing MongoDB operations from the command line within the Constants Catalog environment. It covers connecting to the MongoDB instance, executing CRUD operations, querying by pattern, aggregation, indexing, and bulk writes.

**Audience:** Developers and operations engineers working with the Constants Catalog's MongoDB configuration store.

**Prerequisites:** Docker Compose with the `mongodb` profile running.

---

## Table of Contents

1. [Connecting to the MongoDB Instance](#1-connecting-to-the-mongodb-instance)
2. [Launching the MongoDB Shell](#2-launching-the-mongodb-shell)
3. [Selecting a Database](#3-selecting-a-database)
4. [Document Structure](#4-document-structure)
5. [Insert Operations](#5-insert-operations)
6. [Query Operations](#6-query-operations)
7. [Update Operations](#7-update-operations)
8. [Upsert Operations](#8-upsert-operations)
9. [Delete Operations](#9-delete-operations)
10. [Replace Operations](#10-replace-operations)
11. [Pattern-Based Queries](#11-pattern-based-queries)
12. [Counting and Aggregation](#12-counting-and-aggregation)
13. [Working with the Config Collection](#13-working-with-the-config-collection)
14. [Bulk Write Operations](#14-bulk-write-operations)
15. [Index Management](#15-index-management)
16. [Administration](#16-administration)
17. [Quick Reference](#17-quick-reference)

---

## 1. Connecting to the MongoDB Instance

Ensure the MongoDB service is running:

```bash
docker compose --profile=mongodb up -d mongo
```

Verify the service is healthy:

```bash
docker compose --profile=mongodb ps
```

The status should display `Up (healthy)`.

---

## 2. Launching the MongoDB Shell

### From inside the container

```bash
docker exec -it const-catalog-mongo bash
mongosh
```

### Directly (without entering the container)

```bash
docker exec -it const-catalog-mongo mongosh
```

### From the host machine (requires local mongosh installation)

```bash
mongosh mongodb://localhost:27017
```

---

## 3. Selecting a Database

List all databases:

```javascript
show dbs
```

Switch to the Constants Catalog database:

```javascript
use const-catalog
```

The prompt updates to `const-catalog>`. MongoDB creates the database automatically when data is first written to it.

List collections in the current database:

```javascript
show collections
```

---

## 4. Document Structure

MongoDB stores data as BSON documents (binary JSON). The Constants Catalog's configuration collection uses the following structure:

```json
{
  "_id": "db.host",
  "key": "db.host",
  "value": "localhost"
}
```

- `_id` — Primary key. Enforces uniqueness and provides indexed lookups.
- `key` — Redundant copy of the configuration key for readability.
- `value` — The configuration value as a string.

Documents within a collection are not required to share the same schema.

---

## 5. Insert Operations

### Insert a single document

```javascript
db.servers.insertOne({
  name: "web-01",
  environment: "production",
  ports: [8080, 8443],
  cpu_cores: 4
})
```

MongoDB assigns an auto-generated `ObjectId` if `_id` is not specified.

### Insert with an explicit _id

```javascript
db.servers.insertOne({
  _id: "web-01",
  name: "web-01",
  environment: "production",
  cpu_cores: 4
})
```

### Insert multiple documents

```javascript
db.servers.insertMany([
  { _id: "web-02", name: "web-02", environment: "production", cpu_cores: 8 },
  { _id: "db-01", name: "db-01", environment: "production", cpu_cores: 16 },
  { _id: "dev-01", name: "dev-01", environment: "development", cpu_cores: 2 }
])
```

### Duplicate _id behavior

Attempting to insert a document with an existing `_id` results in an error:

```
E11000 duplicate key error
```

Use an upsert operation ([Section 8](#8-upsert-operations)) to handle insert-or-update logic.

---

## 6. Query Operations

### Retrieve all documents

```javascript
db.servers.find()
```

### Retrieve with formatted output

```javascript
db.servers.find().pretty()
```

### Retrieve by _id

```javascript
db.servers.find({ _id: "web-01" })
```

### Retrieve a single document

```javascript
db.servers.findOne({ _id: "web-01" })
```

`findOne` returns a document directly. `find` returns a cursor.

### Filter by field value

```javascript
db.servers.find({ environment: "production" })
```

### Comparison operators

```javascript
// Servers with more than 4 CPU cores
db.servers.find({ cpu_cores: { $gt: 4 } })

// Servers with 2 to 8 CPU cores (inclusive)
db.servers.find({ cpu_cores: { $gte: 2, $lte: 8 } })
```

| Operator | Description |
|---|---|
| `$eq` | Equal (default when using `{ field: value }`) |
| `$ne` | Not equal |
| `$gt` | Greater than |
| `$gte` | Greater than or equal |
| `$lt` | Less than |
| `$lte` | Less than or equal |
| `$in` | Matches any value in a specified array |
| `$regex` | Regular expression match |

### Multiple conditions (logical AND)

```javascript
db.servers.find({ environment: "production", cpu_cores: { $gt: 4 } })
```

### Logical OR

```javascript
db.servers.find({
  $or: [
    { environment: "production" },
    { cpu_cores: { $gte: 16 } }
  ]
})
```

### Projection (selecting specific fields)

```javascript
// Return only name and cpu_cores, exclude _id
db.servers.find({}, { name: 1, cpu_cores: 1, _id: 0 })
```

### Sorting

```javascript
// Sort by cpu_cores descending
db.servers.find().sort({ cpu_cores: -1 })
```

### Limiting results

```javascript
db.servers.find().sort({ cpu_cores: -1 }).limit(2)
```

---

## 7. Update Operations

### Update a single field

```javascript
db.servers.updateOne(
  { _id: "dev-01" },
  { $set: { environment: "staging" } }
)
```

`$set` modifies only the specified fields. All other fields remain unchanged.

### Update multiple fields

```javascript
db.servers.updateOne(
  { _id: "dev-01" },
  { $set: { environment: "staging", cpu_cores: 4 } }
)
```

### Increment a numeric field

```javascript
db.servers.updateOne(
  { _id: "web-01" },
  { $inc: { cpu_cores: 2 } }
)
```

### Append to an array field

```javascript
db.servers.updateOne(
  { _id: "web-01" },
  { $push: { ports: 9090 } }
)
```

### Update multiple documents

```javascript
db.servers.updateMany(
  { environment: "production" },
  { $set: { monitored: true } }
)
```

### Remove a field

```javascript
db.servers.updateOne(
  { _id: "web-01" },
  { $unset: { ports: "" } }
)
```

### Update operators reference

| Operator | Effect |
|---|---|
| `$set` | Set a field to a specified value |
| `$unset` | Remove a field from the document |
| `$inc` | Increment a numeric field |
| `$push` | Append a value to an array |
| `$pull` | Remove a value from an array |
| `$rename` | Rename a field |

---

## 8. Upsert Operations

An upsert combines insert and update into a single atomic operation. If the filter matches a document, it is updated. If no match is found, a new document is inserted.

```javascript
db.servers.updateOne(
  { _id: "cache-01" },
  { $set: { name: "cache-01", environment: "production", cpu_cores: 8 } },
  { upsert: true }
)
```

Execute the same command with modified data:

```javascript
db.servers.updateOne(
  { _id: "cache-01" },
  { $set: { cpu_cores: 16 } },
  { upsert: true }
)
```

The existing document is updated. The `name` and `environment` fields are preserved because `$set` modifies only the specified fields.

This is the operation pattern used by `MongoConfigStore.set()` in the Constants Catalog — implemented as `replaceOne` with `upsert: true` to store configuration values regardless of whether the key previously existed.

---

## 9. Delete Operations

### Delete a single document

```javascript
db.servers.deleteOne({ _id: "cache-01" })
```

Response: `{ "acknowledged": true, "deletedCount": 1 }`

### Delete multiple documents by filter

```javascript
db.servers.deleteMany({ environment: "development" })
```

### Delete all documents (retain collection and indexes)

```javascript
db.servers.deleteMany({})
```

### Drop a collection (removes collection, documents, and indexes)

```javascript
db.servers.drop()
```

---

## 10. Replace Operations

`replaceOne` replaces the entire document content (except `_id`), unlike `updateOne` with `$set` which modifies individual fields:

```javascript
db.servers.replaceOne(
  { _id: "web-01" },
  { name: "web-01", environment: "production", cpu_cores: 8, region: "us-east-1" }
)
```

After execution, the document contains only `_id`, `name`, `environment`, `cpu_cores`, and `region`. Any fields present before the replacement that are not included in the new document are removed.

### Comparison: updateOne vs replaceOne

| Operation | Behavior |
|---|---|
| `updateOne({ _id: "x" }, { $set: { cpu_cores: 8 } })` | Modifies only `cpu_cores`; all other fields preserved |
| `replaceOne({ _id: "x" }, { cpu_cores: 8 })` | Document becomes `{ _id: "x", cpu_cores: 8 }`; all other fields removed |

The Constants Catalog uses `replaceOne` with `upsert: true` because configuration documents have a fixed structure (`_id`, `key`, `value`) and the complete document is written on every operation.

---

## 11. Pattern-Based Queries

### Regular expression queries

Find all keys with a specific prefix:

```javascript
db.config.find({ _id: { $regex: "^db\\." } })
```

The `^` anchors to the start of the string. `\\.` matches a literal period (`.` in regex matches any character).

Case-insensitive search:

```javascript
db.servers.find({ name: { $regex: "web", $options: "i" } })
```

### Array element queries

Find documents where an array contains a specific value:

```javascript
db.servers.find({ ports: 8080 })
```

MongoDB searches within array fields automatically.

Find documents where an array contains all specified values:

```javascript
db.servers.find({ ports: { $all: [8080, 8443] } })
```

### Field existence queries

```javascript
db.servers.find({ region: { $exists: true } })
```

---

## 12. Counting and Aggregation

### Count documents

```javascript
db.servers.countDocuments({ environment: "production" })
```

### Retrieve distinct values

```javascript
db.servers.distinct("environment")
```

### Aggregation pipeline

Total CPU cores across all servers:

```javascript
db.servers.aggregate([
  { $group: { _id: null, totalCores: { $sum: "$cpu_cores" } } }
])
```

CPU cores grouped by environment:

```javascript
db.servers.aggregate([
  { $group: {
      _id: "$environment",
      count: { $sum: 1 },
      totalCores: { $sum: "$cpu_cores" }
  }},
  { $sort: { totalCores: -1 } }
])
```

---

## 13. Working with the Config Collection

The Constants Catalog stores configuration key-value pairs in the `config` collection. Document structure:

```json
{ "_id": "db.host", "key": "db.host", "value": "localhost" }
```

Common operations:

```javascript
use const-catalog

// List all configuration entries
db.config.find()

// Retrieve a specific key
db.config.findOne({ _id: "db.host" })

// Set a configuration value (upsert)
db.config.replaceOne(
  { _id: "db.host" },
  { _id: "db.host", key: "db.host", value: "localhost" },
  { upsert: true }
)

// Find all database-related configuration
db.config.find({ _id: { $regex: "^db\\." } })

// Count configuration entries
db.config.countDocuments()

// Delete a configuration entry
db.config.deleteOne({ _id: "db.host" })

// Delete all configuration entries
db.config.deleteMany({})
```

---

## 14. Bulk Write Operations

Bulk operations execute multiple write operations in a single network round-trip:

```javascript
db.config.bulkWrite([
  {
    replaceOne: {
      filter: { _id: "db.host" },
      replacement: { _id: "db.host", key: "db.host", value: "prod-db.internal" },
      upsert: true
    }
  },
  {
    replaceOne: {
      filter: { _id: "db.port" },
      replacement: { _id: "db.port", key: "db.port", value: "5432" },
      upsert: true
    }
  },
  {
    replaceOne: {
      filter: { _id: "db.name" },
      replacement: { _id: "db.name", key: "db.name", value: "catalog" },
      upsert: true
    }
  }
])
```

Response includes operation counts:

```json
{
  "insertedCount": 0,
  "matchedCount": 1,
  "modifiedCount": 1,
  "upsertedCount": 2
}
```

---

## 15. Index Management

### List existing indexes

```javascript
db.config.getIndexes()
```

Every collection has a default index on `_id`. The Constants Catalog's access patterns query exclusively on `_id`, so no additional indexes are required.

### Create an index

```javascript
db.config.createIndex({ value: 1 })
```

### Drop an index

```javascript
db.config.dropIndex("value_1")
```

### Analyze query execution

```javascript
db.config.find({ _id: "db.host" }).explain("executionStats")
```

In the output:
- `"stage": "IDHACK"` or `"stage": "IXSCAN"` — query used an index (expected)
- `"stage": "COLLSCAN"` — full collection scan (indicates a missing index for that query pattern)

---

## 16. Administration

### Database statistics

```javascript
db.stats()
```

### Collection statistics

```javascript
db.config.stats()
```

### List active operations

```javascript
db.currentOp()
```

### Drop a database

```javascript
use const-catalog-test
db.dropDatabase()
```

### Exit the shell

```javascript
exit
```

Or use `Ctrl+D`.

### Exit the container

```bash
exit
```

---

## 17. Quick Reference

| Category | Command |
|---|---|
| **Connect** | `docker exec -it const-catalog-mongo mongosh` |
| **Select database** | `use const-catalog` |
| **Insert one** | `db.coll.insertOne({ _id: "k", value: "v" })` |
| **Insert many** | `db.coll.insertMany([ {..}, {..} ])` |
| **Find all** | `db.coll.find()` |
| **Find by key** | `db.coll.find({ _id: "k" })` |
| **Find one** | `db.coll.findOne({ _id: "k" })` |
| **Find by comparison** | `db.coll.find({ n: { $gt: 10 } })` |
| **Find by regex** | `db.coll.find({ _id: /^db\./ })` |
| **Update one field** | `db.coll.updateOne({ _id: "k" }, { $set: { v: "new" } })` |
| **Increment** | `db.coll.updateOne({ _id: "k" }, { $inc: { n: 1 } })` |
| **Update many** | `db.coll.updateMany({ env: "prod" }, { $set: { a: 1 } })` |
| **Upsert** | `db.coll.updateOne({ _id: "k" }, { $set: { v: "v" } }, { upsert: true })` |
| **Replace** | `db.coll.replaceOne({ _id: "k" }, { _id: "k", v: "v" }, { upsert: true })` |
| **Delete one** | `db.coll.deleteOne({ _id: "k" })` |
| **Delete by filter** | `db.coll.deleteMany({ env: "dev" })` |
| **Delete all** | `db.coll.deleteMany({})` |
| **Drop collection** | `db.coll.drop()` |
| **Count** | `db.coll.countDocuments({ env: "prod" })` |
| **Distinct** | `db.coll.distinct("env")` |
| **Aggregate** | `db.coll.aggregate([ { $group: {..} } ])` |
| **Bulk write** | `db.coll.bulkWrite([ { replaceOne: {..} } ])` |
| **List indexes** | `db.coll.getIndexes()` |
| **Explain query** | `db.coll.find().explain("executionStats")` |
| **List databases** | `show dbs` |
| **List collections** | `show collections` |
| **Drop database** | `db.dropDatabase()` |
