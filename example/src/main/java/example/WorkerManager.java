package example;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WorkerManager implements Watcher {

    static final String WORKERS_PATH = "/managed_workers";
    private static final int SESSION_TIMEOUT = 3000;

    private final int targetWorkerCount;
    private final String zookeeperAddress;
    private final String jarPath;
    private final List<Process> childProcesses = new CopyOnWriteArrayList<>();
    private ZooKeeper zooKeeper;

    public WorkerManager(int targetWorkerCount, String zookeeperAddress) {
        this.targetWorkerCount = targetWorkerCount;
        this.zookeeperAddress = zookeeperAddress;
        this.jarPath = new File(
                WorkerManager.class.getProtectionDomain().getCodeSource().getLocation().getPath()
        ).getAbsolutePath();
    }

    public void start() throws IOException, InterruptedException, KeeperException {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        this.zooKeeper = new ZooKeeper(zookeeperAddress, SESSION_TIMEOUT, event -> {
            if (event.getState() == Event.KeeperState.SyncConnected) {
                connectedLatch.countDown();
            }
        });

        if (!connectedLatch.await(10, TimeUnit.SECONDS)) {
            throw new IOException("Failed to connect to ZooKeeper at " + zookeeperAddress);
        }

        if (zooKeeper.exists(WORKERS_PATH, false) == null) {
            zooKeeper.create(WORKERS_PATH, new byte[]{},
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        reconcileWorkers();
    }

    public void stop() throws InterruptedException {
        childProcesses.forEach(Process::destroyForcibly);
        childProcesses.clear();
        if (zooKeeper != null) {
            zooKeeper.close();
            zooKeeper = null;
        }
    }

    public boolean isRunning() {
        return zooKeeper != null && zooKeeper.getState().isAlive();
    }

    public int getTargetWorkerCount() {
        return targetWorkerCount;
    }

    public List<Map<String, String>> getWorkerInfo() {
        if (!isRunning()) {
            return Collections.emptyList();
        }
        try {
            List<String> children = zooKeeper.getChildren(WORKERS_PATH, false);
            List<Map<String, String>> workers = new ArrayList<>();
            for (String child : children) {
                String fullPath = WORKERS_PATH + "/" + child;
                Stat stat = zooKeeper.exists(fullPath, false);
                if (stat == null) continue;
                byte[] data = zooKeeper.getData(fullPath, false, stat);
                Map<String, String> info = new LinkedHashMap<>();
                info.put("znode", child);
                info.put("workerId", new String(data));
                workers.add(info);
            }
            return workers;
        } catch (KeeperException | InterruptedException e) {
            return Collections.emptyList();
        }
    }

    private synchronized void reconcileWorkers() {
        try {
            List<String> children = zooKeeper.getChildren(WORKERS_PATH, this);
            int deficit = targetWorkerCount - children.size();

            if (deficit > 0) {
                for (int i = 0; i < deficit; i++) {
                    spawnWorker();
                }
            }
        } catch (KeeperException | InterruptedException | IOException e) {
            System.err.println("[manager] Error: " + e.getMessage());
        }
    }

    private void spawnWorker() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", jarPath,
                "example.FlakyWorker", zookeeperAddress);
        pb.inheritIO();
        childProcesses.add(pb.start());
    }

    public void run() throws InterruptedException {
        synchronized (zooKeeper) {
            zooKeeper.wait();
        }
    }

    @Override
    public void process(WatchedEvent event) {
        switch (event.getType()) {
            case NodeChildrenChanged:
                reconcileWorkers();
                break;
        }
    }

    public static void main(String[] args) throws Exception {
        int workers = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        String zkAddress = args.length > 1 ? args[1] : "localhost:2181";

        WorkerManager manager = new WorkerManager(workers, zkAddress);
        manager.start();
        manager.run();
    }
}
