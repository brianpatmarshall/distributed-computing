package com.cv5.zookeeper;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/*
 * ZooKeeper Learning Test Suite
 * 
 * This comprehensive test suite demonstrates core ZooKeeper concepts including:
 * - Connection management and session handling
 * - ZNode creation, deletion, and manipulation
 * - Sequential and ephemeral node behaviors
 * - Watchers and event notifications
 * - Resilience patterns and failover scenarios
 * 
 * Prerequisites:
 * - ZooKeeper server running on localhost:2181
 * - Apache ZooKeeper Java client dependency
 * 
 * Test Structure:
 * - Basic connection/disconnection patterns
 * - ZNode lifecycle management
 * - Sequential ephemeral node monitoring
 * - Distributed coordination patterns
 * - Resilience and recovery testing
 */
public class ZooKeeperLearningTests {

    private static final String ZOOKEEPER_CONNECT_STRING = "localhost:2181";
    private static final int SESSION_TIMEOUT = 5000;
    private static final String TEST_ROOT_PATH = "/test-zklearning";
    
    private ZooKeeper zooKeeper;
    private CountDownLatch connectionLatch;

    /*
     * Custom Watcher implementation for learning ZooKeeper event handling
     * 
     * This watcher demonstrates:
     * - Connection state monitoring
     * - Event type handling
     * - Asynchronous event processing patterns
     * - Coordination between test threads and ZK events
     */
    private class TestWatcher implements Watcher {
        private final CountDownLatch latch;
        private volatile Event.KeeperState lastState;
        private volatile Event.EventType lastEventType;
        
        public TestWatcher(CountDownLatch latch) {
            this.latch = latch;
        }
        
        @Override
        public void process(WatchedEvent event) {
            System.out.println("Watcher Event: " + event.getType() + 
                             " | State: " + event.getState() + 
                             " | Path: " + event.getPath());
            
            this.lastState = event.getState();
            this.lastEventType = event.getType();
            
            /*
             * Handle different event types:
             * - SyncConnected: Initial connection established
             * - Disconnected: Connection lost (network issues, server restart)
             * - Expired: Session expired, need to recreate connection
             * - NodeCreated/NodeDeleted: ZNode lifecycle events
             * - NodeDataChanged: ZNode content modifications
             * - NodeChildrenChanged: Child node additions/removals
             */
            switch (event.getState()) {
                case SyncConnected:
                    if (latch != null) latch.countDown();
                    break;
                case Disconnected:
                    System.out.println("ZooKeeper connection lost");
                    break;
                case Expired:
                    System.out.println("ZooKeeper session expired");
                    break;
                default:
                    break;
            }
        }
        
        public Event.KeeperState getLastState() { return lastState; }
        public Event.EventType getLastEventType() { return lastEventType; }
    }

    @BeforeEach
    void setUp() throws IOException, InterruptedException, KeeperException {
        /*
         * Connection Setup Pattern:
         * 
         * ZooKeeper connections are asynchronous by design. The constructor
         * returns immediately, but the actual connection happens in background.
         * We use a CountDownLatch to wait for the SyncConnected event before
         * proceeding with tests.
         * 
         * Key concepts demonstrated:
         * - Asynchronous connection establishment
         * - Session timeout configuration
         * - Connection state monitoring via Watchers
         * - Proper synchronization between test thread and ZK events
         */
        connectionLatch = new CountDownLatch(1);
        TestWatcher connectionWatcher = new TestWatcher(connectionLatch);
        
        zooKeeper = new ZooKeeper(ZOOKEEPER_CONNECT_STRING, SESSION_TIMEOUT, connectionWatcher);
        
        // Wait for connection to be established (max 10 seconds)
        boolean connected = connectionLatch.await(30, TimeUnit.SECONDS);
        assertTrue(connected, "Failed to connect to ZooKeeper within timeout");
        assertEquals(ZooKeeper.States.CONNECTED, zooKeeper.getState());
        
        // Create test root directory if it doesn't exist
        createTestRoot();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        /*
         * Cleanup Pattern:
         * 
         * Proper cleanup is crucial in ZooKeeper testing to avoid:
         * - Resource leaks (open connections)
         * - Test interference (leftover nodes)
         * - Session exhaustion on ZK server
         * 
         * This cleanup demonstrates:
         * - Recursive node deletion
         * - Graceful connection closure
         * - Resource management best practices
         */
        if (zooKeeper != null) {
            try {
                // Clean up test nodes recursively
                deleteRecursively(TEST_ROOT_PATH);
            } catch (Exception e) {
                System.err.println("Cleanup error: " + e.getMessage());
            }
            
            zooKeeper.close();
            // ZooKeeper.close() is asynchronous, give it time to complete
            Thread.sleep(1000);
        }
    }

    /*
     * Test: Basic ZooKeeper Connection and Disconnection
     * 
     * Learning Objectives:
     * - Understand ZooKeeper connection lifecycle
     * - Learn about session management
     * - Practice proper resource cleanup
     * - Understand connection state transitions
     */
    @Test
    @DisplayName("Connect and Disconnect from ZooKeeper")
    void testConnectionLifecycle() throws IOException, InterruptedException {
        /*
         * Connection State Verification:
         *
         * 
         * ZooKeeper maintains several connection states:
         * - CONNECTING: Initial state, attempting connection
         * - CONNECTED: Successfully connected and ready
         * - CLOSED: Connection explicitly closed
         * 
         * Session concepts:
         * - Each connection has a unique session ID
         * - Sessions have timeouts for failure detection
         * - Session state persists briefly after disconnection
         */
        
        // Verify initial connection state
        assertEquals(ZooKeeper.States.CONNECTED, zooKeeper.getState());
        assertNotEquals(0L, zooKeeper.getSessionId());
        assertTrue(zooKeeper.getSessionTimeout() > 0);
        
        System.out.println("Connected - Session ID: " + zooKeeper.getSessionId() + 
                          ", Timeout: " + zooKeeper.getSessionTimeout() + "ms");
        
        /*
         * Graceful Disconnection:
         * 
         * ZooKeeper.close() initiates graceful shutdown:
         * - Sends session close to server
         * - Cleans up client-side resources
         * - Transitions to CLOSED state
         */
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        TestWatcher disconnectWatcher = new TestWatcher(disconnectLatch);
        
        // Create new connection to test disconnection
        ZooKeeper testConnection = new ZooKeeper(ZOOKEEPER_CONNECT_STRING, 
                                                SESSION_TIMEOUT, disconnectWatcher);
        
        // Wait for connection
        assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
        assertEquals(ZooKeeper.States.CONNECTED, testConnection.getState());
        
        // Close connection
        testConnection.close();
        
        // Verify state after close
        Thread.sleep(1000); // Allow time for cleanup
        assertEquals(ZooKeeper.States.CLOSED, testConnection.getState());
    }

    /*
     * Test: ZNode Creation and Basic Operations
     * 
     * Learning Objectives:
     * - Understand ZNode types (persistent, ephemeral, sequential)
     * - Learn ZNode creation patterns
     * - Practice data storage and retrieval
     * - Understand ZNode metadata (Stat objects)
     */
    @Test
    @DisplayName("Create and Manage ZNodes")
    void testZNodeOperations() throws KeeperException, InterruptedException {
        /*
         * ZNode Types and Characteristics:
         * 
         * 1. PERSISTENT: Survives client disconnection
         * 2. EPHEMERAL: Deleted when client session ends
         * 3. SEQUENTIAL: Appends monotonic counter to name
         * 4. PERSISTENT_SEQUENTIAL: Combination of PERSISTENT + SEQUENTIAL
         * 5. EPHEMERAL_SEQUENTIAL: Combination of EPHEMERAL + SEQUENTIAL
         * 
         * Use cases:
         * - PERSISTENT: Configuration data, static information
         * - EPHEMERAL: Presence indication, temporary locks
         * - SEQUENTIAL: Distributed queues, leader election
         */
        
        String testPath = TEST_ROOT_PATH + "/basic-node";
        String testData = "Hello ZooKeeper!";
        
        // Create persistent ZNode
        String createdPath = zooKeeper.create(
            testPath,                           // path
            testData.getBytes(),               // data
            ZooDefs.Ids.OPEN_ACL_UNSAFE,      // ACL (security)
            CreateMode.PERSISTENT             // node type
        );
        
        assertEquals(testPath, createdPath);
        assertTrue(nodeExists(testPath));
        
        /*
         * ZNode Data Operations:
         * 
         * ZNodes can store data (typically small, < 1MB)
         * Common uses: configuration, metadata, coordination info
         * 
         * getData() returns both data and Stat object:
         * - Stat contains version info, timestamps, size, etc.
         * - Useful for conditional updates and monitoring
         */
        
        // Read data and verify
        Stat stat = new Stat();
        byte[] retrievedData = zooKeeper.getData(testPath, false, stat);
        assertEquals(testData, new String(retrievedData));
        
        // Verify Stat information
        assertTrue(stat.getDataLength() > 0);
        assertTrue(stat.getCtime() > 0);  // Creation time
        assertTrue(stat.getMtime() > 0);  // Modification time
        assertEquals(0, stat.getVersion()); // Initial version
        
        System.out.println("ZNode Stats - Version: " + stat.getVersion() + 
                          ", Data Length: " + stat.getDataLength() + 
                          ", Children: " + stat.getNumChildren());
        
        /*
         * ZNode Updates and Versioning:
         * 
         * ZooKeeper implements optimistic locking via versions:
         * - Each update increments version number
         * - Can specify expected version for conditional updates
         * - Prevents lost update problems in distributed systems
         */
        
        String updatedData = "Updated ZooKeeper data!";
        Stat updateStat = zooKeeper.setData(testPath, updatedData.getBytes(), 
                                          stat.getVersion()); // Use current version
        
        assertEquals(1, updateStat.getVersion()); // Version incremented
        
        // Verify update
        byte[] newData = zooKeeper.getData(testPath, false, null);
        assertEquals(updatedData, new String(newData));
        
        // Test version conflict (should fail)
        assertThrows(KeeperException.BadVersionException.class, () -> {
            zooKeeper.setData(testPath, "conflict".getBytes(), 0); // Wrong version
        });
        
        // Delete the node
        zooKeeper.delete(testPath, updateStat.getVersion());
        assertFalse(nodeExists(testPath));
    }

    /*
     * Test: Sequential Ephemeral ZNodes for Distributed Coordination
     * 
     * Learning Objectives:
     * - Understand sequential node numbering
     * - Learn ephemeral node lifecycle
     * - Practice distributed coordination patterns
     * - Implement basic leader election concepts
     */
    @Test
    @DisplayName("Sequential Ephemeral ZNodes and Monitoring")
    void testSequentialEphemeralNodes() throws KeeperException, InterruptedException {
        /*
         * Sequential Ephemeral Nodes Pattern:
         * 
         * This is a fundamental ZooKeeper pattern used for:
         * - Leader election
         * - Distributed queues
         * - Resource allocation
         * - Load balancing
         * 
         * Key characteristics:
         * - Sequential: ZK appends 10-digit sequence number
         * - Ephemeral: Automatically deleted when session ends
         * - Ordered: Sequence numbers are monotonically increasing
         * - Unique: Each node gets unique sequence number
         */
        
        String basePath = TEST_ROOT_PATH + "/sequential";
        String nodePrefix = basePath + "/node-";
        
        // Create multiple sequential ephemeral nodes
        String[] createdNodes = new String[5];
        for (int i = 0; i < 5; i++) {
            createdNodes[i] = zooKeeper.create(
                nodePrefix,                        // base path
                ("Node " + i + " data").getBytes(), // data
                ZooDefs.Ids.OPEN_ACL_UNSAFE,      // ACL
                CreateMode.EPHEMERAL_SEQUENTIAL    // ephemeral + sequential
            );
            
            System.out.println("Created sequential node: " + createdNodes[i]);
        }
        
        /*
         * Sequential Number Verification:
         * 
         * ZooKeeper guarantees:
         * - Sequence numbers are unique per parent
         * - Numbers are monotonically increasing
         * - 10-digit format with zero padding
         * - Global ordering across all clients
         */
        
        // Verify sequential ordering
        List<String> children = zooKeeper.getChildren(basePath, false);
        assertEquals(5, children.size());
        
        // Children should be ordered by sequence number
        children.sort(String::compareTo);
        for (int i = 1; i < children.size(); i++) {
            String prev = children.get(i - 1);
            String curr = children.get(i);
            
            // Extract sequence numbers (last 10 digits)
            int prevSeq = Integer.parseInt(prev.substring(prev.length() - 10));
            int currSeq = Integer.parseInt(curr.substring(curr.length() - 10));
            
            assertTrue(currSeq > prevSeq, 
                      "Sequential numbers should be increasing: " + prev + " -> " + curr);
        }
        
        /*
         * Watcher Pattern for Monitoring:
         * 
         * Watchers are crucial for distributed coordination:
         * - One-time triggers (need to re-register)
         * - Asynchronous event delivery
         * - Various event types (NodeDeleted, NodeChildrenChanged, etc.)
         * - Enable reactive distributed algorithms
         */
        
        CountDownLatch childChangeEvent = new CountDownLatch(1);
        AtomicInteger childChangeCount = new AtomicInteger(0);
        
        Watcher childWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                System.out.println("Child watcher triggered: " + event.getType() + 
                                 " on " + event.getPath());
                
                if (event.getType() == Event.EventType.NodeChildrenChanged) {
                    childChangeCount.incrementAndGet();
                    childChangeEvent.countDown();
                }
            }
        };
        
        // Register watcher on parent directory
        List<String> initialChildren = zooKeeper.getChildren(basePath, childWatcher);
        assertEquals(5, initialChildren.size());
        
        /*
         * Leader Election Simulation:
         * 
         * Basic leader election algorithm:
         * 1. All nodes create sequential ephemeral nodes
         * 2. Node with smallest sequence number becomes leader
         * 3. Others watch the node immediately before them
         * 4. When watched node disappears, re-check if now leader
         * 
         * This demonstrates self-healing distributed coordination.
         */
        
        // Find the "leader" (smallest sequence number)
        String leader = children.stream().min(String::compareTo).orElse("");
        System.out.println("Current leader: " + leader);
        
        // Simulate leader failure by deleting the leader node
        String leaderPath = basePath + "/" + leader;
        Stat leaderStat = zooKeeper.exists(leaderPath, false);
        assertNotNull(leaderStat);
        
        zooKeeper.delete(leaderPath, leaderStat.getVersion());
        
        // Wait for child change event
        assertTrue(childChangeEvent.await(5, TimeUnit.SECONDS), 
                  "Should receive child change notification");
        assertEquals(1, childChangeCount.get());
        
        // Verify leader is gone and new leader emerged
        List<String> afterDeletion = zooKeeper.getChildren(basePath, false);
        assertEquals(4, afterDeletion.size());
        assertFalse(afterDeletion.contains(leader));
        
        String newLeader = afterDeletion.stream().min(String::compareTo).orElse("");
        assertNotEquals(leader, newLeader);
        System.out.println("New leader after failover: " + newLeader);
        
        /*
         * Ephemeral Node Lifecycle Verification:
         * 
         * Ephemeral nodes are tied to the client session:
         * - Created when session is active
         * - Automatically deleted when session ends
         * - Used for presence detection and failure detection
         * - Cannot have children (ZK limitation)
         */
        
        // Verify all remaining nodes are ephemeral
        for (String child : afterDeletion) {
            String childPath = basePath + "/" + child;
            Stat childStat = zooKeeper.exists(childPath, false);
            assertNotNull(childStat);
            assertTrue(childStat.getEphemeralOwner() != 0, 
                      "Node should be ephemeral: " + child);
            assertEquals(zooKeeper.getSessionId(), childStat.getEphemeralOwner());
        }
    }

    /*
     * Test: Resilience and Failover Scenarios
     * 
     * Learning Objectives:
     * - Understand ZooKeeper fault tolerance
     * - Practice connection recovery patterns
     * - Learn session expiration handling
     * - Implement resilient distributed applications
     */
    @Test
    @DisplayName("Node Resilience and Automatic Recovery")
    void testResilienceAndRecovery() throws Exception {
        /*
         * Resilience Testing Strategy:
         * 
         * This test simulates real-world failure scenarios:
         * 1. Network partitions (connection loss)
         * 2. Session expiration
         * 3. Automatic recovery mechanisms
         * 4. Data consistency after recovery
         * 
         * ZooKeeper guarantees:
         * - Sequential consistency
         * - Atomicity of updates
         * - Durability of committed changes
         * - Availability during minority failures
         */
        
        String resilientPath = TEST_ROOT_PATH + "/resilient";
        String nodeBasePath = resilientPath + "/service-";
        
        // Create initial service registration
        String serviceNode = zooKeeper.create(
            nodeBasePath,
            "Service-1-Data".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.EPHEMERAL_SEQUENTIAL
        );
        
        System.out.println("Initial service node: " + serviceNode);
        assertTrue(nodeExists(serviceNode));
        
        /*
         * Connection Recovery Pattern:
         * 
         * Robust ZooKeeper applications must handle:
         * - Temporary connection loss
         * - Session expiration
         * - Automatic reconnection
         * - State reconstruction after recovery
         */
        
        CountDownLatch reconnectLatch = new CountDownLatch(1);
        CountDownLatch sessionExpiredLatch = new CountDownLatch(1);
        AtomicBoolean sessionExpired = new AtomicBoolean(false);
        
        /*
         * Recovery Watcher:
         * 
         * This watcher demonstrates proper handling of:
         * - Connection state changes
         * - Session expiration events
         * - Reconnection success
         * - Recovery coordination
         */
        Watcher recoveryWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                System.out.println("Recovery watcher: " + event.getState() + 
                                 " | " + event.getType());
                
                switch (event.getState()) {
                    case SyncConnected:
                        if (sessionExpired.get()) {
                            // Successful reconnection after session expiration
                            reconnectLatch.countDown();
                        }
                        break;
                        
                    case Expired:
                        sessionExpired.set(true);
                        sessionExpiredLatch.countDown();
                        System.out.println("Session expired - will attempt recovery");
                        break;
                        
                    case Disconnected:
                        System.out.println("Disconnected - ZK will try to reconnect");
                        break;
                        
                    default:
                        break;
                }
            }
        };
        
        /*
         * Simulate Service Failure and Recovery:
         * 
         * This pattern is common in distributed systems:
         * 1. Service registers ephemeral node (presence indicator)
         * 2. Service fails (connection lost, session expired)
         * 3. Ephemeral node automatically deleted
         * 4. Service restarts and re-registers
         * 5. New ephemeral node created
         */
        
        // Create recovery connection that will simulate failure
        ZooKeeper recoveryConnection = new ZooKeeper(
            ZOOKEEPER_CONNECT_STRING, 
            1000, // Very short timeout to trigger expiration
            recoveryWatcher
        );
        
        // Wait for initial connection
        Thread.sleep(2000);
        assertEquals(ZooKeeper.States.CONNECTED, recoveryConnection.getState());
        
        // Create service node with recovery connection
        String recoveryServiceNode = recoveryConnection.create(
            nodeBasePath,
            "Recovery-Service-Data".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.EPHEMERAL_SEQUENTIAL
        );
        
        System.out.println("Recovery service node: " + recoveryServiceNode);
        
        // Verify both service nodes exist
        List<String> servicesBeforeFailure = zooKeeper.getChildren(resilientPath, false);
        assertEquals(2, servicesBeforeFailure.size());
        
        /*
         * Force Session Expiration:
         * 
         * We simulate session expiration by:
         * 1. Closing connection abruptly
         * 2. Waiting for session timeout
         * 3. Observing ephemeral node deletion
         * 4. Implementing recovery logic
         */
        
        long recoverySessionId = recoveryConnection.getSessionId();
        System.out.println("Forcing expiration of session: " + recoverySessionId);
        
        // Close connection to force session expiration
        recoveryConnection.close();
        
        // Wait for session expiration (timeout + grace period)
        Thread.sleep(3000);
        
        // Verify ephemeral node was deleted due to session expiration
        List<String> servicesAfterExpiration = zooKeeper.getChildren(resilientPath, false);
        assertEquals(1, servicesAfterExpiration.size());
        assertFalse(nodeExists(recoveryServiceNode));
        
        System.out.println("Service node deleted due to session expiration");
        
        /*
         * Automatic Recovery Implementation:
         * 
         * Resilient applications implement recovery by:
         * 1. Detecting session expiration
         * 2. Creating new ZooKeeper connection
         * 3. Re-registering ephemeral nodes
         * 4. Restoring application state
         * 5. Resuming normal operations
         */
        
        // Simulate service restart with new connection
        ZooKeeper newRecoveryConnection = new ZooKeeper(
            ZOOKEEPER_CONNECT_STRING,
            SESSION_TIMEOUT,
            recoveryWatcher
        );
        
        // Wait for new connection
        Thread.sleep(2000);
        assertEquals(ZooKeeper.States.CONNECTED, newRecoveryConnection.getState());
        
        // Re-register service (recovery complete)
        String newRecoveryServiceNode = newRecoveryConnection.create(
            nodeBasePath,
            "Recovered-Service-Data".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.EPHEMERAL_SEQUENTIAL
        );
        
        System.out.println("Service recovered with new node: " + newRecoveryServiceNode);
        
        // Verify service is back online
        List<String> servicesAfterRecovery = zooKeeper.getChildren(resilientPath, false);
        assertEquals(2, servicesAfterRecovery.size());
        assertTrue(nodeExists(newRecoveryServiceNode));
        
        // Verify new session ID (proves complete recovery)
        assertNotEquals(recoverySessionId, newRecoveryConnection.getSessionId());
        
        /*
         * Recovery Validation:
         * 
         * Proper recovery should ensure:
         * - Service availability restored
         * - New unique session established
         * - Ephemeral nodes properly recreated
         * - Application state consistent
         * - Monitoring/watchers re-established
         */
        
        // Verify data integrity after recovery
        byte[] recoveredData = newRecoveryConnection.getData(newRecoveryServiceNode, false, null);
        assertEquals("Recovered-Service-Data", new String(recoveredData));
        
        System.out.println("Recovery test completed successfully");
        
        // Cleanup recovery connection
        newRecoveryConnection.close();
    }

    /*
     * Test: Advanced ZooKeeper Patterns
     * 
     * Learning Objectives:
     * - Implement distributed barrier pattern
     * - Practice group membership management
     * - Learn configuration management patterns
     * - Understand distributed synchronization
     */
    @Test
    @DisplayName("Advanced Distributed Coordination Patterns")
    void testAdvancedPatterns() throws Exception {
        /*
         * Distributed Barrier Pattern:
         * 
         * Barriers synchronize distributed processes:
         * 1. Processes register at barrier
         * 2. Wait until minimum number reached
         * 3. All processes proceed together
         * 4. Useful for coordinated starts, batch processing
         */
        
        String barrierPath = TEST_ROOT_PATH + "/barrier";
        String participantPath = barrierPath + "/participant-";
        int barrierSize = 3;
        
        CountDownLatch barrierReached = new CountDownLatch(1);
        AtomicInteger participantCount = new AtomicInteger(0);
        
        /*
         * Barrier Watcher:
         * 
         * Monitors barrier state and signals when ready:
         * - Counts current participants
         * - Triggers when threshold reached
         * - Handles participant failures
         */
        Watcher barrierWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getType() == Event.EventType.NodeChildrenChanged) {
                    try {
                        List<String> participants = zooKeeper.getChildren(barrierPath, this);
                        int count = participants.size();
                        participantCount.set(count);
                        
                        System.out.println("Barrier participants: " + count + "/" + barrierSize);
                        
                        if (count >= barrierSize) {
                            System.out.println("Barrier threshold reached!");
                            barrierReached.countDown();
                        }
                    } catch (Exception e) {
                        System.err.println("Barrier watcher error: " + e.getMessage());
                    }
                }
            }
        };
        
        // Initialize barrier monitoring
        zooKeeper.getChildren(barrierPath, barrierWatcher);
        
        // Register participants
        String[] participants = new String[barrierSize];
        for (int i = 0; i < barrierSize; i++) {
            participants[i] = zooKeeper.create(
                participantPath,
                ("Participant-" + i).getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL_SEQUENTIAL
            );
            
            System.out.println("Registered participant: " + participants[i]);
            Thread.sleep(500); // Simulate distributed timing
        }
        
        // Wait for barrier to be reached
        assertTrue(barrierReached.await(10, TimeUnit.SECONDS), 
                  "Barrier should be reached within timeout");
        assertEquals(barrierSize, participantCount.get());
        
        /*
         * Group Membership Pattern:
         * 
         * Track active members in distributed system:
         * - Members create ephemeral nodes
         * - Automatic cleanup on failure
         * - Real-time membership updates
         * - Load balancer backend registration
         */
        
        String groupPath = TEST_ROOT_PATH + "/group";
        CountDownLatch membershipChange = new CountDownLatch(2); // Expect 2 changes
        
        Watcher membershipWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getType() == Event.EventType.NodeChildrenChanged) {
                    try {
                        List<String> members = zooKeeper.getChildren(groupPath, this);
                        System.out.println("Group membership changed: " + members.size() + " members");
                        membershipChange.countDown();
                    } catch (Exception e) {
                        System.err.println("Membership watcher error: " + e.getMessage());
                    }
                }
            }
        };
        
        // Initialize membership monitoring
        List<String> initialMembers = zooKeeper.getChildren(groupPath, membershipWatcher);
        assertEquals(0, initialMembers.size());
        
        // Add group member
        String member1 = zooKeeper.create(
            groupPath + "/member-",
            "Member-1-Info".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.EPHEMERAL_SEQUENTIAL
        );
        
        // Add second member
        String member2 = zooKeeper.create(
            groupPath + "/member-",
            "Member-2-Info".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.EPHEMERAL_SEQUENTIAL
        );
        
        // Wait for membership notifications
        assertTrue(membershipChange.await(5, TimeUnit.SECONDS),
                  "Should receive membership change notifications");
        
        // Verify final membership
        List<String> finalMembers = zooKeeper.getChildren(groupPath, false);
        assertEquals(2, finalMembers.size());
        
        System.out.println("Advanced patterns test completed successfully");
    }

    // Helper methods for test infrastructure

    private void createTestRoot() throws KeeperException, InterruptedException {
        /*
         * Test Environment Setup:
         * 
         * Creates isolated test namespace:
         * - Prevents interference between tests
         * - Provides clean slate for each test
         * - Enables parallel test execution
         * - Simplifies cleanup operations
         */
        if (zooKeeper.exists(TEST_ROOT_PATH, false) == null) {
            zooKeeper.create(
                TEST_ROOT_PATH,
                "Test root for ZooKeeper learning".getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT
            );
        }
        
        // Create subdirectories for different test categories
        String[] testDirs = {"/sequential", "/resilient", "/barrier", "/group"};
        for (String dir : testDirs) {
            String fullPath = TEST_ROOT_PATH + dir;
            if (zooKeeper.exists(fullPath, false) == null) {
                zooKeeper.create(
                    fullPath,
                    ("Test directory: " + dir).getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT
                );
            }
        }
    }

    private boolean nodeExists(String path) throws KeeperException, InterruptedException {
        /*
         * Node Existence Check:
         * 
         * exists() method returns:
         * - Stat object if node exists
         * - null if node doesn't exist
         * - Can optionally set watcher for future existence changes
         * 
         * This is atomic operation - no race conditions between
         * check and subsequent operations
         */
        return zooKeeper.exists(path, false) != null;
    }

    private void deleteRecursively(String path) throws KeeperException, InterruptedException {
        /*
         * Recursive Deletion Utility:
         * 
         * ZooKeeper requires bottom-up deletion:
         * 1. Delete all children first
         * 2. Then delete parent node
         * 3. Cannot delete non-empty nodes
         * 
         * This pattern is essential for cleanup in tests
         * and application shutdown procedures.
         */
        try {
            List<String> children = zooKeeper.getChildren(path, false);
            
            // Delete all children first (depth-first)
            for (String child : children) {
                String childPath = path + "/" + child;
                deleteRecursively(childPath);
            }
            
            // Delete the node itself
            zooKeeper.delete(path, -1); // -1 means ignore version
            System.out.println("Deleted: " + path);
            
        } catch (KeeperException.NoNodeException e) {
            // Node already deleted, ignore
            System.out.println("Node already deleted: " + path);
        } catch (KeeperException.NotEmptyException e) {
            // Retry after brief delay
            Thread.sleep(100);
            deleteRecursively(path);
        }
    }

    /*
     * Test: Complex Failure Scenarios and Edge Cases
     * 
     * Learning Objectives:
     * - Handle concurrent modifications
     * - Deal with network partitions
     * - Understand split-brain scenarios
     * - Practice defensive programming patterns
     */
    @Test
    @DisplayName("Complex Failure Scenarios and Edge Cases")
    void testComplexFailureScenarios() throws Exception {
        /*
         * Concurrent Access Pattern:
         * 
         * Multiple clients modifying same ZNode:
         * - Version conflicts
         * - Race conditions
         * - Optimistic locking
         * - Retry strategies
         */
        
        String concurrentPath = TEST_ROOT_PATH + "/concurrent-test";
        String initialData = "Initial Data";
        
        // Create test node
        zooKeeper.create(
            concurrentPath,
            initialData.getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT
        );
        
        /*
         * Simulate Concurrent Updates:
         * 
         * Two clients trying to update same node:
         * 1. Both read current version
         * 2. Both prepare updates
         * 3. First update succeeds
         * 4. Second update fails with BadVersionException
         * 5. Second client must retry with new version
         */
        
        // Create second connection for concurrent access
        CountDownLatch secondConnectionReady = new CountDownLatch(1);
        ZooKeeper secondConnection = new ZooKeeper(
            ZOOKEEPER_CONNECT_STRING,
            SESSION_TIMEOUT,
            event -> {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    secondConnectionReady.countDown();
                }
            }
        );
        
        assertTrue(secondConnectionReady.await(5, TimeUnit.SECONDS));
        
        // Both connections read initial state
        Stat stat1 = new Stat();
        Stat stat2 = new Stat();
        
        byte[] data1 = zooKeeper.getData(concurrentPath, false, stat1);
        byte[] data2 = secondConnection.getData(concurrentPath, false, stat2);
        
        assertEquals(initialData, new String(data1));
        assertEquals(initialData, new String(data2));
        assertEquals(stat1.getVersion(), stat2.getVersion());
        
        /*
         * First update succeeds
         */
        String update1 = "Update from connection 1";
        Stat newStat1 = zooKeeper.setData(
            concurrentPath, 
            update1.getBytes(), 
            stat1.getVersion()
        );
        assertEquals(stat1.getVersion() + 1, newStat1.getVersion());
        
        /*
         * Second update fails due to version conflict
         */
        String update2 = "Update from connection 2";
        assertThrows(KeeperException.BadVersionException.class, () -> {
            secondConnection.setData(
                concurrentPath, 
                update2.getBytes(), 
                stat2.getVersion() // Stale version
            );
        });
        
        /*
         * Retry Strategy Implementation:
         * 
         * Proper retry pattern for version conflicts:
         * 1. Catch BadVersionException
         * 2. Re-read current data and version
         * 3. Apply business logic to new data
         * 4. Retry update with current version
         * 5. Repeat until success or max attempts
         */
        
        boolean retrySuccess = false;
        int maxRetries = 3;
        int retryCount = 0;
        
        while (!retrySuccess && retryCount < maxRetries) {
            try {
                // Re-read current state
                Stat currentStat = new Stat();
                byte[] currentData = secondConnection.getData(concurrentPath, false, currentStat);
                
                // Apply update to current data (business logic here)
                String combinedUpdate = new String(currentData) + " + " + update2;
                
                // Attempt update with current version
                secondConnection.setData(
                    concurrentPath,
                    combinedUpdate.getBytes(),
                    currentStat.getVersion()
                );
                
                retrySuccess = true;
                System.out.println("Retry successful on attempt: " + (retryCount + 1));
                
            } catch (KeeperException.BadVersionException e) {
                retryCount++;
                System.out.println("Retry failed, attempt: " + retryCount);
                Thread.sleep(100); // Brief backoff
            }
        }
        
        assertTrue(retrySuccess, "Retry strategy should eventually succeed");
        
        // Verify final state
        byte[] finalData = zooKeeper.getData(concurrentPath, false, null);
        String finalString = new String(finalData);
        assertTrue(finalString.contains(update1));
        assertTrue(finalString.contains(update2));
        
        secondConnection.close();
    }

    /*
     * Test: Performance and Scalability Patterns
     * 
     * Learning Objectives:
     * - Understand ZooKeeper performance characteristics
     * - Learn batch operations and efficiency
     * - Practice monitoring and metrics collection
     * - Implement performance optimization strategies
     */
    @Test
    @DisplayName("Performance and Scalability Testing")
    void testPerformancePatterns() throws Exception {
        /*
         * Batch Operations Pattern:
         * 
         * ZooKeeper performance optimization:
         * - Individual operations have overhead
         * - Batch related operations when possible
         * - Use multi-operations (transactions) for atomicity
         * - Monitor operation latencies
         */
        
        String perfTestPath = TEST_ROOT_PATH + "/performance";
        int nodeCount = 10;
        
        /*
         * Performance Measurement:
         * 
         * Measure different operation types:
         * - Sequential node creation
         * - Batch node creation
         * - Data retrieval patterns
         * - Watch registration overhead
         */
        
        long startTime = System.currentTimeMillis();
        
        // Create nodes sequentially (baseline measurement)
        for (int i = 0; i < nodeCount; i++) {
            zooKeeper.create(
                  perfTestPath + "/seq-" + i,
                  ("Sequential node " + i).getBytes(),
                  ZooDefs.Ids.OPEN_ACL_UNSAFE,
                  CreateMode.PERSISTENT
            );
        }
        long sequentialTime = System.currentTimeMillis() - startTime;
        System.out.println("Sequential creation time: " + sequentialTime + "ms for " + nodeCount + " nodes");
        
        /*
         * Bulk Data Retrieval Pattern:
         * 
         * Efficient ways to read multiple nodes:
         * - Get children list first
         * - Parallel data retrieval
         * - Batch processing of results
         * - Connection pooling for high throughput
         */
        
        startTime = System.currentTimeMillis();
        
        List<String> children = zooKeeper.getChildren(perfTestPath, false);
        Map<String, String> nodeData = new ConcurrentHashMap<>();
        
        // Use parallel streams for concurrent data retrieval
        children.parallelStream().forEach(child -> {
            try {
                String childPath = perfTestPath + "/" + child;
                byte[] data = zooKeeper.getData(childPath, false, null);
                nodeData.put(child, new String(data));
            } catch (Exception e) {
                System.err.println("Error reading child: " + child);
            }
        });
        
        long bulkReadTime = System.currentTimeMillis() - startTime;
        System.out.println("Bulk read time: " + bulkReadTime + "ms for " + nodeData.size() + " nodes");
        
        assertEquals(nodeCount, nodeData.size());
        
        /*
         * Watch Registration Performance:
         * 
         * Understanding watch overhead:
         * - Each watch has memory cost
         * - Event delivery has latency
         * - Mass watch registration can impact performance
         * - Consider watch consolidation strategies
         */
        
        CountDownLatch watchEvents = new CountDownLatch(nodeCount);
        AtomicLong watchSetupTime = new AtomicLong();
        
        startTime = System.currentTimeMillis();
        
        // Register watches on all nodes
        for (String child : children) {
            String childPath = perfTestPath + "/" + child;
            zooKeeper.exists(childPath, event -> {
                if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                    watchEvents.countDown();
                }
            });
        }
        
        watchSetupTime.set(System.currentTimeMillis() - startTime);
        System.out.println("Watch registration time: " + watchSetupTime.get() + "ms for " + nodeCount + " watches");
        
        /*
         * Mass Event Generation:
         * 
         * Test event delivery performance:
         * - Delete all watched nodes
         * - Measure event delivery time
         * - Verify all events received
         * - Check for event ordering
         */
        
        startTime = System.currentTimeMillis();
        
        // Delete all nodes to trigger watches
        for (String child : children) {
            String childPath = perfTestPath + "/" + child;
            try {
                zooKeeper.delete(childPath, -1);
            } catch (KeeperException.NoNodeException e) {
                // Already deleted, ignore
            }
        }
        
        // Wait for all watch events
        boolean allEventsReceived = watchEvents.await(30, TimeUnit.SECONDS);
        long eventDeliveryTime = System.currentTimeMillis() - startTime;
        
        assertTrue(allEventsReceived, "All watch events should be delivered");
        System.out.println("Event delivery time: " + eventDeliveryTime + "ms for " + nodeCount + " events");
        
        /*
         * Performance Analysis:
         * 
         * Calculate key performance metrics:
         * - Operations per second
         * - Average latency per operation
         * - Event delivery throughput
         * - Memory usage patterns
         */
        
        double createOpsPerSec = (double) nodeCount / sequentialTime * 1000;
        double readOpsPerSec = (double) nodeCount / bulkReadTime * 1000;
        double eventOpsPerSec = (double) nodeCount / eventDeliveryTime * 1000;
        
        System.out.println("Performance Summary:");
        System.out.println("- Create ops/sec: " + String.format("%.2f", createOpsPerSec));
        System.out.println("- Read ops/sec: " + String.format("%.2f", readOpsPerSec));
        System.out.println("- Event ops/sec: " + String.format("%.2f", eventOpsPerSec));
        
        // Verify reasonable performance (these are rough benchmarks)
        assertTrue(createOpsPerSec > 10, "Create performance should be reasonable");
        assertTrue(readOpsPerSec > 50, "Read performance should be reasonable");
        assertTrue(eventOpsPerSec > 20, "Event delivery should be reasonable");
    }

    /*
     * Test: Security and ACL Patterns
     * 
     * Learning Objectives:
     * - Understand ZooKeeper Access Control Lists (ACLs)
     * - Learn authentication schemes
     * - Practice secure node operations
     * - Implement authorization patterns
     */
    @Test
    @DisplayName("Security and Access Control Testing")
    void testSecurityPatterns() throws Exception {
        /*
         * ACL (Access Control List) Fundamentals:
         * 
         * ZooKeeper ACL components:
         * - Scheme: Authentication method (world, auth, digest, ip)
         * - ID: Identity within scheme
         * - Permissions: READ, WRITE, CREATE, DELETE, ADMIN
         * 
         * Common ACL patterns:
         * - OPEN_ACL_UNSAFE: World-readable/writable (development only)
         * - READ_ACL_UNSAFE: World-readable, creator-writable
         * - CREATOR_ALL_ACL: Only creator has all permissions
         */
        
        String securityPath = TEST_ROOT_PATH + "/security-test";
        
        /*
         * World Scheme ACLs:
         * 
         * "world" scheme with "anyone" ID:
         * - Most permissive
         * - Suitable for public configuration
         * - Never use in production for sensitive data
         */
        
        ArrayList<ACL> worldReadable = new ArrayList<>();
        worldReadable.add(new ACL(ZooDefs.Perms.READ, new Id("world", "anyone")));
        worldReadable.add(new ACL(ZooDefs.Perms.WRITE, new Id("auth", "")));
        
        String publicNode = zooKeeper.create(
            securityPath + "/public",
            "Public configuration data".getBytes(),
            worldReadable,
            CreateMode.PERSISTENT
        );
        
        // Verify ACLs were set correctly
        List<ACL> retrievedACLs = zooKeeper.getACL(publicNode, null);
        assertTrue(retrievedACLs.size() >= 1);
        
        /*
         * Digest Authentication:
         * 
         * Username:password based authentication:
         * - Passwords are hashed with SHA1
         * - Suitable for service-to-service authentication
         * - Can create user-specific permissions
         */
        
        // Note: In real applications, use proper authentication
        // This is simplified for testing purposes
        String digestAuth = "testuser:testpass";
        
        ArrayList<ACL> restrictedACL = new ArrayList<>();
        restrictedACL.add(new ACL(ZooDefs.Perms.ALL, new Id("world", "anyone")));
        
        String restrictedNode = zooKeeper.create(
            securityPath + "/restricted",
            "Restricted data".getBytes(),
            restrictedACL,
            CreateMode.PERSISTENT
        );
        
        /*
         * IP-based Access Control:
         * 
         * Restrict access by client IP address:
         * - Useful for network-based security
         * - Can specify IP ranges
         * - Combines well with other schemes
         */
        
        ArrayList<ACL> ipRestrictedACL = new ArrayList<>();
        ipRestrictedACL.add(new ACL(ZooDefs.Perms.READ, new Id("ip", "127.0.0.1")));
        ipRestrictedACL.add(new ACL(ZooDefs.Perms.ALL, new Id("world", "anyone")));
        
        String ipRestrictedNode = zooKeeper.create(
            securityPath + "/ip-restricted",
            "IP restricted data".getBytes(),
            ipRestrictedACL,
            CreateMode.PERSISTENT
        );
        
        // Verify we can read our own IP-restricted node
        byte[] ipRestrictedData = zooKeeper.getData(ipRestrictedNode, false, null);
        assertEquals("IP restricted data", new String(ipRestrictedData));
        
        /*
         * ACL Inheritance and Management:
         * 
         * Important ACL concepts:
         * - Child nodes inherit parent ACLs by default
         * - ACLs can be modified after creation
         * - Different permissions for different operations
         * - ADMIN permission required to change ACLs
         */
        
        // Create child node (inherits parent ACLs)
        String childNode = zooKeeper.create(
            ipRestrictedNode + "/child",
            "Child node data".getBytes(),
            ipRestrictedACL,
            CreateMode.PERSISTENT
        );
        
        // Verify child has same ACLs
        List<ACL> childACLs = zooKeeper.getACL(childNode, null);
        List<ACL> parentACLs = zooKeeper.getACL(ipRestrictedNode, null);
        assertEquals(parentACLs.size(), childACLs.size());
        
        System.out.println("Security patterns test completed successfully");
    }

    /*
     * Test: Configuration Management Patterns
     * 
     * Learning Objectives:
     * - Implement distributed configuration
     * - Learn configuration change propagation
     * - Practice version management
     * - Understand configuration hierarchies
     */
    @Test
    @DisplayName("Configuration Management Patterns")
    void testConfigurationManagement() throws Exception {
        /*
         * Hierarchical Configuration Pattern:
         * 
         * Organize configuration in tree structure:
         * - Environment-specific configs
         * - Application-specific configs
         * - Component-specific configs
         * - Override mechanisms
         */
        
        String configRoot = TEST_ROOT_PATH + "/config";
        
        // Create configuration hierarchy
        String[] configPaths = {
            configRoot + "/global",
            configRoot + "/environments/dev",
            configRoot + "/environments/prod", 
            configRoot + "/applications/webapp",
            configRoot + "/applications/api"
        };
        
        for (String path : configPaths) {
            createPath(path);
        }
        
        /*
         * Configuration Data Management:
         * 
         * Store configuration as JSON or properties:
         * - Structured data format
         * - Easy parsing and validation
         * - Version tracking
         * - Change history
         */
        
        String globalConfig = """
            {
                "database": {
                    "maxConnections": 100,
                    "timeout": 5000
                },
                "logging": {
                    "level": "INFO"
                }
            }
            """;
        
        String devConfig = """
            {
                "database": {
                    "host": "dev-db.example.com",
                    "maxConnections": 10
                },
                "logging": {
                    "level": "DEBUG"
                }
            }
            """;
        
        // Store configurations
        zooKeeper.create(
            configRoot + "/global/app-config",
            globalConfig.getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT
        );
        
        zooKeeper.create(
            configRoot + "/environments/dev/app-config",
            devConfig.getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT
        );
        
        /*
         * Configuration Change Monitoring:
         * 
         * Applications watch for config changes:
         * - Real-time configuration updates
         * - No application restarts needed
         * - Gradual rollout capabilities
         * - Rollback mechanisms
         */
        
        CountDownLatch configChange = new CountDownLatch(1);
        AtomicBoolean configUpdated = new AtomicBoolean(false);
        
        Watcher configWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getType() == Event.EventType.NodeDataChanged) {
                    System.out.println("Configuration changed: " + event.getPath());
                    configUpdated.set(true);
                    configChange.countDown();
                }
            }
        };
        
        // Monitor global config for changes
        String globalConfigPath = configRoot + "/global/app-config";
        zooKeeper.getData(globalConfigPath, configWatcher, null);
        
        /*
         * Configuration Update Pattern:
         * 
         * Safe configuration updates:
         * 1. Validate new configuration
         * 2. Update with version check
         * 3. Notify watchers
         * 4. Monitor for issues
         * 5. Rollback if necessary
         */
        
        String updatedGlobalConfig = """
            {
                "database": {
                    "maxConnections": 200,
                    "timeout": 5000,
                    "poolName": "main-pool"
                },
                "logging": {
                    "level": "WARN"
                }
            }
            """;
        
        // Update configuration (triggers watcher)
        Stat configStat = zooKeeper.exists(globalConfigPath, false);
        zooKeeper.setData(globalConfigPath, updatedGlobalConfig.getBytes(), 
                         configStat.getVersion());
        
        // Wait for change notification
        assertTrue(configChange.await(5, TimeUnit.SECONDS),
                  "Configuration change should be detected");
        assertTrue(configUpdated.get());
        
        // Verify updated configuration
        byte[] newConfigData = zooKeeper.getData(globalConfigPath, false, null);
        String newConfigString = new String(newConfigData);
        assertTrue(newConfigString.contains("poolName"));
        assertTrue(newConfigString.contains("200"));
        
        /*
         * Configuration Inheritance Pattern:
         * 
         * Implement configuration override hierarchy:
         * 1. Start with global defaults
         * 2. Apply environment-specific overrides
         * 3. Apply application-specific overrides  
         * 4. Apply runtime overrides
         */
        
        // Simulate configuration resolution
        Map<String, Object> resolvedConfig = resolveConfiguration(
            configRoot + "/global/app-config",
            configRoot + "/environments/dev/app-config"
        );
        
        assertNotNull(resolvedConfig);
        System.out.println("Configuration management test completed successfully");
    }
    
    // Helper method to create path with parents
    private void createPath(String path) throws KeeperException, InterruptedException {
        String[] parts = path.split("/");
        StringBuilder currentPath = new StringBuilder();
        
        for (String part : parts) {
            if (part.isEmpty()) continue;
            
            currentPath.append("/").append(part);
            String pathToCreate = currentPath.toString();
            
            if (zooKeeper.exists(pathToCreate, false) == null) {
                zooKeeper.create(
                    pathToCreate,
                    ("Directory: " + part).getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT
                );
            }
        }
    }
    
    // Simplified configuration resolution (would be more complex in real apps)
    private Map<String, Object> resolveConfiguration(String... configPaths) 
            throws KeeperException, InterruptedException {
        Map<String, Object> resolved = new HashMap<>();
        
        for (String configPath : configPaths) {
            if (zooKeeper.exists(configPath, false) != null) {
                byte[] configData = zooKeeper.getData(configPath, false, null);
                // In real implementation, would parse JSON and merge
                resolved.put(configPath, new String(configData));
            }
        }
        
        return resolved;
    }
}