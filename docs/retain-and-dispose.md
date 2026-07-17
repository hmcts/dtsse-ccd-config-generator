# Retain and dispose

Services are responsible for implementing the appropriate retention and deletion policies for their case types.

For example, draft cases may be deleted after a period of inactivity.

Decentralised services may express their retention and deletion policies in code (ie. SQL queries) rather than configuration.

## Retain and dispose for decentralised services

Decentralised services are responsible for the deletion of case data they hold within their databases.

The retain and dispose component remains responsible for:

1. Orchestrating the deletion of related data held by other common components, such as case documents.
2. Retaining cases where appropriate eg.
   3. expired cases that are related to live cases via case links
   4. expired cases that are subject to retention override

### Disposal overview

1. Decentralised service implements a nightly task to identify candidate cases for disposal
   2. eg. ```select cases where state = 'draft' and created_at < now() - interval '1 year'```
3. For each candidate case:
   4. Trigger a ccd event to transition that case into a terminal 'Deleting' state
      5. This event should have a ttl increment of 0 to indicate immediate deletion
      6. If eligible retain and dispose will delete
         7. associated case data held by other common components (eg. documents)
         8. the case pointer from ccd's database
5. For each case in the terminal state:
   6. GET the case using CCD's api (using a system user with R on the terminal state)
   7. Delete the case's local data if CCD returns a 404

Note that:

1. Only the event that transitions a case to the terminal 'Deleting' state features a TTL increment; policy is implemented in code rather than configuration.
