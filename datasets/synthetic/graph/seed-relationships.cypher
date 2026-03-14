// ─── Chapter 12 Graph Seed — Relationships ────────────────────────────────────
// Run AFTER seed-nodes.cypher
// 30 relationships: OWNS, CONNECTED_TO, USES_DEVICE, SHARED_DEVICE, TRANSFERRED_TO

// ── OWNS: Customer → Account ──────────────────────────────────────────────────
MATCH (c:Customer {id: 'customer-001'}), (a:Account {id: 'account-001'})
MERGE (c)-[r:OWNS]->(a) SET r.since = '2022-01-15', r.strength = 0.95, r.chapter = '12';

MATCH (c:Customer {id: 'customer-002'}), (a:Account {id: 'account-002'})
MERGE (c)-[r:OWNS]->(a) SET r.since = '2024-03-10', r.strength = 0.90, r.chapter = '12';

MATCH (c:Customer {id: 'customer-003'}), (a:Account {id: 'account-003'})
MERGE (c)-[r:OWNS]->(a) SET r.since = '2026-02-20', r.strength = 0.88, r.chapter = '12';

MATCH (c:Customer {id: 'customer-004'}), (a:Account {id: 'account-004'})
MERGE (c)-[r:OWNS]->(a) SET r.since = '2023-07-01', r.strength = 0.80, r.chapter = '12';

MATCH (c:Customer {id: 'customer-005'}), (a:Account {id: 'account-005'})
MERGE (c)-[r:OWNS]->(a) SET r.since = '2026-02-25', r.strength = 0.92, r.chapter = '12';

MATCH (c:Customer {id: 'customer-006'}), (a:Account {id: 'account-006'})
MERGE (c)-[r:OWNS]->(a) SET r.since = '2022-11-30', r.strength = 0.85, r.chapter = '12';

MATCH (c:Customer {id: 'customer-007'}), (a:Account {id: 'account-006'})
MERGE (c)-[r:OWNS]->(a) SET r.since = '2025-08-14', r.strength = 0.70, r.chapter = '12';

MATCH (c:Customer {id: 'customer-008'}), (a:Account {id: 'account-007'})
MERGE (c)-[r:OWNS]->(a) SET r.since = '2026-02-27', r.strength = 0.91, r.chapter = '12';

// ── USES_DEVICE: Customer → Device ───────────────────────────────────────────
MATCH (c:Customer {id: 'customer-001'}), (d:Device {id: 'device-001'})
MERGE (c)-[r:USES_DEVICE]->(d) SET r.lastSeen = '2026-03-01', r.strength = 0.80, r.chapter = '12';

MATCH (c:Customer {id: 'customer-002'}), (d:Device {id: 'device-002'})
MERGE (c)-[r:USES_DEVICE]->(d) SET r.lastSeen = '2026-02-28', r.strength = 0.75, r.chapter = '12';

MATCH (c:Customer {id: 'customer-003'}), (d:Device {id: 'device-003'})
MERGE (c)-[r:USES_DEVICE]->(d) SET r.lastSeen = '2026-03-02', r.strength = 0.95, r.chapter = '12';

MATCH (c:Customer {id: 'customer-005'}), (d:Device {id: 'device-003'})
MERGE (c)-[r:USES_DEVICE]->(d) SET r.lastSeen = '2026-03-02', r.strength = 0.92, r.chapter = '12';

MATCH (c:Customer {id: 'customer-008'}), (d:Device {id: 'device-004'})
MERGE (c)-[r:USES_DEVICE]->(d) SET r.lastSeen = '2026-03-02', r.strength = 0.89, r.chapter = '12';

// ── SHARED_DEVICE: marks same device used by multiple customers (fraud signal) ─
MATCH (c1:Customer {id: 'customer-003'}), (c2:Customer {id: 'customer-005'})
MERGE (c1)-[r:SHARED_DEVICE]->(c2) SET r.deviceId = 'device-003', r.strength = 0.93, r.chapter = '12';

MATCH (c1:Customer {id: 'customer-005'}), (c2:Customer {id: 'customer-008'})
MERGE (c1)-[r:SHARED_DEVICE]->(c2) SET r.deviceId = 'device-004', r.strength = 0.88, r.chapter = '12';

// ── CONNECTED_TO: peer/cluster relationships (the Chapter 12 cluster signal) ──
MATCH (c1:Customer {id: 'customer-003'}), (c2:Customer {id: 'customer-005'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.90, r.strength = 0.90, r.chapter = '12';

MATCH (c1:Customer {id: 'customer-005'}), (c2:Customer {id: 'customer-007'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.75, r.strength = 0.75, r.chapter = '12';

MATCH (c1:Customer {id: 'customer-007'}), (c2:Customer {id: 'customer-008'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.85, r.strength = 0.85, r.chapter = '12';

MATCH (c1:Customer {id: 'customer-008'}), (c2:Customer {id: 'customer-003'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.88, r.strength = 0.88, r.chapter = '12';

MATCH (c1:Customer {id: 'customer-003'}), (c2:Customer {id: 'customer-008'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.91, r.strength = 0.91, r.chapter = '12';

// High cluster: customer-005 connects to 4 others (triggers risk elevation in tests)
MATCH (c1:Customer {id: 'customer-005'}), (c2:Customer {id: 'customer-003'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.82, r.strength = 0.82, r.chapter = '12';

MATCH (c1:Customer {id: 'customer-005'}), (c2:Customer {id: 'customer-006'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.76, r.strength = 0.76, r.chapter = '12';

// ── TRANSFERRED_TO: Account → Account ────────────────────────────────────────
MATCH (a1:Account {id: 'account-003'}), (a2:Account {id: 'account-005'})
MERGE (a1)-[r:TRANSFERRED_TO]->(a2)
SET r.amount = 9500.00, r.currency = 'USD', r.strength = 0.88, r.chapter = '12',
    r.timestamp = '2026-02-28T22:15:00Z';

MATCH (a1:Account {id: 'account-005'}), (a2:Account {id: 'account-001'})
MERGE (a1)-[r:TRANSFERRED_TO]->(a2)
SET r.amount = 45000.00, r.currency = 'USD', r.strength = 0.92, r.chapter = '12',
    r.timestamp = '2026-03-01T01:30:00Z';

MATCH (a1:Account {id: 'account-007'}), (a2:Account {id: 'account-003'})
MERGE (a1)-[r:TRANSFERRED_TO]->(a2)
SET r.amount = 2000.00, r.currency = 'JPY', r.strength = 0.70, r.chapter = '12',
    r.timestamp = '2026-03-02T08:00:00Z';

// ── LINKED_ACCOUNT: co-ownership signal ───────────────────────────────────────
MATCH (c:Customer {id: 'customer-003'}), (a:Account {id: 'account-005'})
MERGE (c)-[r:LINKED_ACCOUNT]->(a) SET r.strength = 0.80, r.chapter = '12';

MATCH (c:Customer {id: 'customer-005'}), (a:Account {id: 'account-003'})
MERGE (c)-[r:LINKED_ACCOUNT]->(a) SET r.strength = 0.85, r.chapter = '12';

// ── low-risk baseline connections ─────────────────────────────────────────────
MATCH (c1:Customer {id: 'customer-001'}), (c2:Customer {id: 'customer-002'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.30, r.strength = 0.30, r.chapter = '12';

MATCH (c1:Customer {id: 'customer-004'}), (c2:Customer {id: 'customer-006'})
MERGE (c1)-[r:CONNECTED_TO]->(c2) SET r.weight = 0.25, r.strength = 0.25, r.chapter = '12';
