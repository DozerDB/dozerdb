///////////////////////////////////////////////////////////////////////////
// NEO4J ENTERPRISE CONSTRAINT TEST SUITE
// Each section can be run independently.
// Run cleanup between sections if needed.
///////////////////////////////////////////////////////////////////////////


///////////////////////////////////////////////////////////////////////////
// 0) GLOBAL CLEANUP (OPTIONAL)
///////////////////////////////////////////////////////////////////////////

MATCH (n)
WHERE any(l IN labels(n) WHERE l STARTS WITH 'Test')
DETACH DELETE n;

DROP CONSTRAINT test_exists_email IF EXISTS;
DROP CONSTRAINT test_exists_createdAt IF EXISTS;
DROP CONSTRAINT test_rel_exists_since IF EXISTS;
DROP CONSTRAINT test_unique_email IF EXISTS;
DROP CONSTRAINT test_unique_name_tenant IF EXISTS;
DROP CONSTRAINT test_nodekey_ext_tenant IF EXISTS;
DROP CONSTRAINT test_unique_username IF EXISTS;



///////////////////////////////////////////////////////////////////////////
// 1) NODE PROPERTY EXISTENCE CONSTRAINT
// email must exist
///////////////////////////////////////////////////////////////////////////

CREATE CONSTRAINT test_exists_email
FOR (p:TestNodeExistsEmail)
REQUIRE p.email IS NOT NULL;


// --- SHOULD FAIL (missing email)
CREATE (p:TestNodeExistsEmail {name: "No Email"});


// --- SHOULD PASS
CREATE (p:TestNodeExistsEmail {
  name: "Has Email",
  email: "has.email@example.com"
});



///////////////////////////////////////////////////////////////////////////
// 2) NODE PROPERTY EXISTENCE CONSTRAINT
// createdAt must exist
///////////////////////////////////////////////////////////////////////////

CREATE CONSTRAINT test_exists_createdAt
FOR (p:TestNodeExistsCreatedAt)
REQUIRE p.createdAt IS NOT NULL;


// --- SHOULD FAIL (missing createdAt)
CREATE (p:TestNodeExistsCreatedAt {
  email: "missing.createdAt@example.com"
});


// --- SHOULD PASS
CREATE (p:TestNodeExistsCreatedAt {
  email: "ok.createdAt@example.com",
  createdAt: datetime()
});



///////////////////////////////////////////////////////////////////////////
// 3) RELATIONSHIP PROPERTY EXISTENCE CONSTRAINT
// since must exist
///////////////////////////////////////////////////////////////////////////

CREATE CONSTRAINT test_rel_exists_since
FOR ()-[r:TEST_REL_SINCE]-()
REQUIRE r.since IS NOT NULL;


// --- SHOULD FAIL (relationship missing since)
CREATE (:TestRelNode {id: "A"})
       -[:TEST_REL_SINCE]->
       (:TestRelNode {id: "B"});


// --- SHOULD PASS
CREATE (:TestRelNode {id: "C"})
       -[:TEST_REL_SINCE {since: date()}]->
       (:TestRelNode {id: "D"});



///////////////////////////////////////////////////////////////////////////
// 4) SINGLE PROPERTY UNIQUENESS CONSTRAINT
// email must be unique
///////////////////////////////////////////////////////////////////////////

CREATE CONSTRAINT test_unique_email
FOR (p:TestUniqueEmail)
REQUIRE p.email IS UNIQUE;


// --- SHOULD PASS (first insert)
CREATE (:TestUniqueEmail {
  email: "unique@example.com",
  name: "First"
});


// --- SHOULD FAIL (duplicate email)
CREATE (:TestUniqueEmail {
  email: "unique@example.com",
  name: "Duplicate"
});



///////////////////////////////////////////////////////////////////////////
// 5) COMPOSITE UNIQUENESS CONSTRAINT
// (name, tenantId) must be unique together
///////////////////////////////////////////////////////////////////////////

CREATE CONSTRAINT test_unique_name_tenant
FOR (p:TestCompositeUnique)
REQUIRE (p.name, p.tenantId) IS UNIQUE;


// --- SHOULD PASS (different tenantId)
CREATE (:TestCompositeUnique {name: "Alice", tenantId: "t1"});
CREATE (:TestCompositeUnique {name: "Alice", tenantId: "t2"});


// --- SHOULD FAIL (same name + same tenantId)
CREATE (:TestCompositeUnique {name: "Alice", tenantId: "t1"});



///////////////////////////////////////////////////////////////////////////
// 6) NODE KEY CONSTRAINT (ENTERPRISE FEATURE)
// (externalId, tenantId) must BOTH exist AND be unique together
///////////////////////////////////////////////////////////////////////////

CREATE CONSTRAINT test_nodekey_ext_tenant
FOR (p:TestNodeKey)
REQUIRE (p.externalId, p.tenantId) IS NODE KEY;


// --- SHOULD FAIL (missing tenantId)
CREATE (:TestNodeKey {externalId: "ext-1"});


// --- SHOULD PASS
CREATE (:TestNodeKey {
  externalId: "ext-1",
  tenantId: "t1",
  payload: "ok"
});


// --- SHOULD FAIL (duplicate key pair)
CREATE (:TestNodeKey {
  externalId: "ext-1",
  tenantId: "t1",
  payload: "duplicate"
});


// --- SHOULD PASS (same externalId, different tenantId allowed)
CREATE (:TestNodeKey {
  externalId: "ext-1",
  tenantId: "t2",
  payload: "ok"
});



///////////////////////////////////////////////////////////////////////////
// 7) USERNAME UNIQUENESS CONSTRAINT
// username must be unique
///////////////////////////////////////////////////////////////////////////

CREATE CONSTRAINT test_unique_username
FOR (u:TestUser)
REQUIRE u.username IS UNIQUE;


// --- SHOULD PASS
CREATE (:TestUser {
  username: "neo",
  email: "neo@example.com"
});


// --- SHOULD FAIL (duplicate username)
CREATE (:TestUser {
  username: "neo",
  email: "neo2@example.com"
});



///////////////////////////////////////////////////////////////////////////
// 8) INSPECT CONSTRAINTS
///////////////////////////////////////////////////////////////////////////

SHOW CONSTRAINTS;

MATCH (n)
WHERE any(l IN labels(n) WHERE l STARTS WITH 'Test')
RETURN labels(n) AS labels, count(*) AS cnt
ORDER BY labels;

///////////////////////////////////////////////////////////////////////////
// END OF TEST SUITE
///////////////////////////////////////////////////////////////////////////
