// ─── Chapter 12 Graph Seed — Entity Nodes ────────────────────────────────────
// Run via: cypher-shell -u neo4j -p password < seed-nodes.cypher
// Or paste in the Neo4j Browser at http://localhost:7474
//
// Creates 20 entity nodes: 8 Customer, 7 Account, 5 Device

// ── Clear existing Chapter 12 test data (dev only!) ──────────────────────────
MATCH (n) WHERE n.chapter = '12' DETACH DELETE n;

// ── Customer nodes ────────────────────────────────────────────────────────────
MERGE (c1:Customer {id: 'customer-001'})
SET c1 += {name: 'Alice Marchetti',  riskScore: 0.15, kycStatus: 'VERIFIED',
           accountAgeDays: 1200, country: 'IT', chapter: '12', strength: 0.9};

MERGE (c2:Customer {id: 'customer-002'})
SET c2 += {name: 'Bob Keller',       riskScore: 0.42, kycStatus: 'VERIFIED',
           accountAgeDays: 365, country: 'DE', chapter: '12', strength: 0.7};

MERGE (c3:Customer {id: 'customer-003'})
SET c3 += {name: 'Carlos Ruiz',      riskScore: 0.78, kycStatus: 'PENDING',
           accountAgeDays: 22, country: 'MX', chapter: '12', strength: 0.85};

MERGE (c4:Customer {id: 'customer-004'})
SET c4 += {name: 'Diana Okafor',     riskScore: 0.31, kycStatus: 'VERIFIED',
           accountAgeDays: 720, country: 'NG', chapter: '12', strength: 0.6};

MERGE (c5:Customer {id: 'customer-005'})
SET c5 += {name: 'Erik Svensson',    riskScore: 0.88, kycStatus: 'UNVERIFIED',
           accountAgeDays: 5,   country: 'SE', chapter: '12', strength: 0.95};

MERGE (c6:Customer {id: 'customer-006'})
SET c6 += {name: 'Fatima Al-Hassan', riskScore: 0.22, kycStatus: 'VERIFIED',
           accountAgeDays: 900, country: 'AE', chapter: '12', strength: 0.75};

MERGE (c7:Customer {id: 'customer-007'})
SET c7 += {name: 'George Patel',     riskScore: 0.55, kycStatus: 'VERIFIED',
           accountAgeDays: 180, country: 'IN', chapter: '12', strength: 0.65};

MERGE (c8:Customer {id: 'customer-008'})
SET c8 += {name: 'Hana Tanaka',      riskScore: 0.91, kycStatus: 'UNVERIFIED',
           accountAgeDays: 3,   country: 'JP', chapter: '12', strength: 0.92};

// ── Account nodes ─────────────────────────────────────────────────────────────
MERGE (a1:Account {id: 'account-001'})
SET a1 += {balance: 12500.00, currency: 'EUR', type: 'CURRENT',
           flagged: false, chapter: '12', strength: 0.8};

MERGE (a2:Account {id: 'account-002'})
SET a2 += {balance: 320.50,  currency: 'EUR', type: 'SAVINGS',
           flagged: false, chapter: '12', strength: 0.7};

MERGE (a3:Account {id: 'account-003'})
SET a3 += {balance: 0.01,    currency: 'USD', type: 'CURRENT',
           flagged: true,  chapter: '12', strength: 0.95};

MERGE (a4:Account {id: 'account-004'})
SET a4 += {balance: 4800.00, currency: 'GBP', type: 'CURRENT',
           flagged: false, chapter: '12', strength: 0.6};

MERGE (a5:Account {id: 'account-005'})
SET a5 += {balance: 99000.00, currency: 'USD', type: 'INVESTMENT',
           flagged: true,  chapter: '12', strength: 0.88};

MERGE (a6:Account {id: 'account-006'})
SET a6 += {balance: 1200.00, currency: 'EUR', type: 'CURRENT',
           flagged: false, chapter: '12', strength: 0.72};

MERGE (a7:Account {id: 'account-007'})
SET a7 += {balance: 550.00,  currency: 'JPY', type: 'SAVINGS',
           flagged: false, chapter: '12', strength: 0.68};

// ── Device nodes ──────────────────────────────────────────────────────────────
MERGE (d1:Device {id: 'device-001'})
SET d1 += {deviceType: 'MOBILE',  os: 'iOS',     ip: '192.168.1.10',
           vpnDetected: false, chapter: '12', strength: 0.5};

MERGE (d2:Device {id: 'device-002'})
SET d2 += {deviceType: 'DESKTOP', os: 'Windows', ip: '10.0.0.5',
           vpnDetected: false, chapter: '12', strength: 0.6};

MERGE (d3:Device {id: 'device-003'})
SET d3 += {deviceType: 'MOBILE',  os: 'Android', ip: '203.0.113.42',
           vpnDetected: true,  chapter: '12', strength: 0.9};

MERGE (d4:Device {id: 'device-004'})
SET d4 += {deviceType: 'TABLET',  os: 'Android', ip: '198.51.100.7',
           vpnDetected: true,  chapter: '12', strength: 0.85};

MERGE (d5:Device {id: 'device-005'})
SET d5 += {deviceType: 'MOBILE',  os: 'iOS',     ip: '172.16.0.1',
           vpnDetected: false, chapter: '12', strength: 0.55};
